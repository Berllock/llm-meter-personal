package com.llmeter.personal.provider.codex.service.reader;

import com.llmeter.personal.provider.domain.ProviderCollectionFailure;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.exception.ProviderCollectionException;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.quota.dto.QuotaWindow;
import com.llmeter.personal.quota.service.QuotaForecastCalculator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Reads quota evidence separately; never sums percentages across sessions or scopes. */
public final class CodexQuotaReader {
    private final JsonMapper mapper = new JsonMapper();
    private static final int MAX_OBSERVATIONS_PER_SCOPE = 256;
    private final Clock clock;
    private final QuotaForecastCalculator forecasts = new QuotaForecastCalculator();
    private record Observation(Instant time, String plan, String scope, String account, List<QuotaWindow> windows) { }

    public CodexQuotaReader() { this(Clock.systemUTC()); }
    public CodexQuotaReader(final Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public Optional<QuotaSnapshot> read(final List<Path> files) {
        Observation latest = null;
        final var scopes = new HashMap<String, Observation>();
        final Map<String, TreeMap<Instant, Observation>> history = new HashMap<>();
        final Set<String> accounts = new HashSet<>();
        for (final var file : files) {
            String accountId = null;
            try (final var reader = Files.newBufferedReader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    final JsonNode row;
                    try { row = mapper.readTree(line); }
                    catch (final JacksonException e) { throw failure(); }
                    if (row == null || !row.isObject()) throw failure();
                    final var payload = row.path("payload");
                    if ("session_meta".equals(row.path("type").asString())) {
                        final var account = payload.path("creator_account_id");
                        accountId = account.isString() && !account.stringValue().isBlank() ? account.stringValue() : null;
                        if (accountId != null) accounts.add(accountId);
                    }
                    if (!"event_msg".equals(row.path("type").asString())
                            || !"token_count".equals(payload.path("type").asString())) continue;
                    final var limits = payload.path("rate_limits");
                    if (limits.isMissingNode() || limits.isNull()) continue;
                    if (!limits.isObject()) throw failure();
                    final Instant time;
                    try { time = Instant.parse(row.path("timestamp").asString()); }
                    catch (final DateTimeException | NullPointerException e) { throw failure(); }
                    final String plan = label(limits.path("plan_type"));
                    final String scope = label(limits.path("limit_id"));
                    final var windows = new ArrayList<QuotaWindow>();
                    for (final String name : List.of("primary", "secondary")) {
                        final var window = limits.path(name);
                        if (window.isMissingNode() || window.isNull()) continue;
                        final var used = window.path("used_percent");
                        final var minutes = window.path("window_minutes");
                        final var reset = window.path("resets_at");
                        if (!window.isObject() || !used.isNumber() || !minutes.isIntegralNumber() || !minutes.canConvertToLong()
                                || !reset.isIntegralNumber() || !reset.canConvertToLong() || reset.longValue() < 0) throw failure();
                        try { windows.add(new QuotaWindow(name, used.doubleValue(), minutes.longValue(), Instant.ofEpochSecond(reset.longValue()), scope, time)); }
                        catch (final IllegalArgumentException | DateTimeException e) { throw failure(); }
                    }
                    final var observation = new Observation(time, plan, scope, accountId, List.copyOf(windows));
                    remember(history.computeIfAbsent(scope, (final var ignored) -> new TreeMap<>()), observation);
                    final var previous = scopes.get(scope);
                    if (previous == null || time.isAfter(previous.time())) scopes.put(scope, observation);
                    else if (time.equals(previous.time()) && !observation.equals(previous)) throw failure();
                    if (latest == null || time.isAfter(latest.time())) latest = observation;
                    else if (time.equals(latest.time()) && !Objects.equals(plan, latest.plan())) throw failure();
                }
            } catch (final IOException e) {
                throw new ProviderCollectionException(ProviderCollectionFailure.READ_FAILURE, "Could not read Codex quota observations", e);
            }
        }
        if (accounts.size() > 1) throw failure();
        if (latest == null) return Optional.empty();
        final String latestPlan = latest.plan();
        final var collectedAt = clock.instant();
        final var windows = scopes.values().stream().filter((final var value) -> Objects.equals(value.plan(), latestPlan))
                .flatMap((final var value) -> value.windows().stream().map((final var window) ->
                        withForecast(value, window, history.get(value.scope()), collectedAt))).sorted(Comparator
                        .comparing(QuotaWindow::limitId, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(QuotaWindow::name)).toList();
        if (windows.isEmpty()) return Optional.empty();
        return Optional.of(new QuotaSnapshot(ProviderType.CODEX, null, null, null, collectedAt, latest.time(),
                latest.plan(), scopes.size() == 1 ? latest.scope() : null, windows));
    }

    /** Retains only bounded recent source observations; polling the same bytes adds no samples. */
    private void remember(final TreeMap<Instant, Observation> timeline, final Observation observation) {
        final var duplicate = timeline.putIfAbsent(observation.time(), observation);
        if (duplicate != null && !duplicate.equals(observation)) throw failure();
        final Instant cutoff;
        try { cutoff = timeline.lastKey().minus(QuotaForecastCalculator.LOOKBACK); }
        catch (final DateTimeException e) { throw failure(); }
        timeline.headMap(cutoff, false).clear();
        while (timeline.size() > MAX_OBSERVATIONS_PER_SCOPE) timeline.pollFirstEntry();
    }

    private QuotaWindow withForecast(final Observation current, final QuotaWindow window,
                                    final TreeMap<Instant, Observation> timeline, final Instant now) {
        final var samples = new ArrayList<QuotaWindow>();
        // Stop at plan/account/reset changes or explicit missing windows; never bridge incompatible history.
        for (final var observation : timeline.descendingMap().values()) {
            if (!Objects.equals(current.account(), observation.account()) || !Objects.equals(current.plan(), observation.plan())) break;
            final var compatible = observation.windows().stream().filter((final var candidate) -> candidate.name().equals(window.name())
                    && Objects.equals(candidate.windowMinutes(), window.windowMinutes()) && candidate.resetAt().equals(window.resetAt())).findFirst();
            if (compatible.isEmpty()) break;
            samples.add(compatible.orElseThrow());
        }
        Collections.reverse(samples);
        final boolean verified = current.account() != null && current.plan() != null && current.scope() != null;
        return new QuotaWindow(window.name(), window.usedPercent(), window.windowMinutes(), window.resetAt(), window.limitId(),
                window.observedAt(), forecasts.calculate(samples, now, verified));
    }

    private String label(final JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString() || !value.stringValue().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) throw failure();
        return value.stringValue();
    }
    private ProviderCollectionException failure() {
        return new ProviderCollectionException(ProviderCollectionFailure.CONFLICTING_DATA, "Codex quota evidence is unavailable or ambiguous");
    }
}
