package com.llmeter.personal.quota.service.history;

import com.llmeter.personal.provider.FakeLlmProviderAdapter;
import com.llmeter.personal.provider.domain.ProviderType;
import com.llmeter.personal.provider.codex.service.reader.CodexQuotaReader;
import com.llmeter.personal.quota.domain.QuotaAccountEvidence;
import com.llmeter.personal.quota.dto.QuotaForecast;
import com.llmeter.personal.quota.dto.QuotaHistoryEntry;
import com.llmeter.personal.quota.dto.QuotaSnapshot;
import com.llmeter.personal.quota.dto.QuotaWindow;
import com.llmeter.personal.quota.service.QuotaForecastCalculator;
import com.llmeter.personal.quota.service.QuotaService;
import com.llmeter.personal.usage.dto.UsageSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static com.llmeter.personal.quota.dto.QuotaForecast.Status.*;
import static com.llmeter.personal.quota.dto.QuotaForecast.Source.*;

class QuotaHistoryForecastsTest {
    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
    private static final String CONTEXT = QuotaHistoryCodec.hash("synthetic-source");
    private final QuotaHistoryForecasts forecasts = new QuotaHistoryForecasts();
    private final QuotaHistoryCodec codec = new QuotaHistoryCodec();
    private final QuotaComparabilityPolicy policy = new QuotaComparabilityPolicy();
    @TempDir Path directory;

    private QuotaSnapshot sample(final long seconds, final double used) {
        return sample(seconds, used, "synthetic-account", "plus", "codex", NOW.plusSeconds(3600), 300);
    }
    private QuotaSnapshot sample(final long seconds, final double used, final String account, final String plan,
                                 final String scope, final Instant reset, final long minutes) {
        final var time = NOW.plusSeconds(seconds);
        final var window = new QuotaWindow("primary", used, minutes, reset, scope, time);
        return new QuotaSnapshot(ProviderType.CODEX, null, null, null, NOW, time, plan, scope,
                List.of(new QuotaWindow("primary", used, minutes, reset, scope, time,
                        new QuotaForecastCalculator().calculate(List.of(window), NOW, account != null).withSource(NATIVE_LOGS))),
                null, 0, null, account == null ? null : new QuotaAccountEvidence(account));
    }
    private QuotaHistoryEntry entry(final QuotaSnapshot snapshot) {
        final var fingerprint = policy.accountFingerprint(snapshot);
        final var reduced = codec.reduced(snapshot);
        return new QuotaHistoryEntry(codec.identity(CONTEXT, reduced, fingerprint), NOW, fingerprint != null, reduced,
                policy.evaluate(CONTEXT, reduced, fingerprint, false));
    }
    private QuotaForecast result(final QuotaSnapshot current, final QuotaSnapshot... prior) {
        return forecasts.enrich(current, CONTEXT, Arrays.stream(prior).map(this::entry).toList(), NOW).windows().getFirst().forecast();
    }
    private QuotaHistoryService history(final String context) {
        return new QuotaHistoryService(new QuotaHistoryStore(directory), Map.of(ProviderType.CODEX, context), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private String nativeRow(final long seconds, final double used, final String plan, final boolean empty) {
        final var limits = empty ? "" : ",\"primary\":{\"used_percent\":" + used + ",\"window_minutes\":300,\"resets_at\":" + NOW.plusSeconds(3600).getEpochSecond() + "}";
        return "{\"type\":\"event_msg\",\"timestamp\":\"" + NOW.plusSeconds(seconds) + "\",\"payload\":{\"type\":\"token_count\",\"rate_limits\":{\"plan_type\":\"" + plan + "\",\"limit_id\":\"codex\"" + limits + "}}}";
    }

    // Validates archive samples restore a sparse native forecast and keep projections anchored to the current source timestamp.
    @Test void shouldProjectFromSavedHistory() {
        final var result = result(sample(0, 40), sample(-240, 20), sample(-120, 30));
        assertThat(result.status()).isEqualTo(ESTIMATED);
        assertThat(result.source()).isEqualTo(SAVED_HISTORY);
        assertThat(result.sampleCount()).isEqualTo(3);
        assertThat(result.percentagePointsPerHour()).isEqualTo(300);
        assertThat(result.projectedExhaustionAt()).isEqualTo(NOW.plusSeconds(720));
        assertThat(result.sampleTo()).isEqualTo(NOW);
    }

    // Validates polling, legacy copies and changes to another window cannot fabricate independent source samples.
    @Test void shouldDeduplicatePerWindowTimestamps() {
        final var entries = new ArrayList<QuotaHistoryEntry>();
        final var older = sample(-120, 30);
        for (int index = 0; index < 110; index++) {
            final var copy = entry(older);
            entries.add(new QuotaHistoryEntry(copy.id(), NOW.plusSeconds(index), true, copy.snapshot(), copy.comparability()));
        }
        final var current = sample(0, 40);
        final var forecast = forecasts.enrich(current, CONTEXT, entries, NOW).windows().getFirst().forecast();
        assertThat(forecast.status()).isEqualTo(INSUFFICIENT_DATA);
        assertThat(forecast.sampleCount()).isEqualTo(2);
        assertThat(forecast.source()).isEqualTo(SAVED_HISTORY);
    }

    // Validates another window advancing the root timestamp cannot duplicate a primary sample or supply missing secondary history.
    @Test void shouldKeepWindowEvidenceIndependent() {
        final var older = sample(-120, 30);
        final var secondary = new QuotaWindow("secondary", 50, 300, NOW.plusSeconds(3600), "codex", NOW.minusSeconds(60));
        final var changedOtherWindow = new QuotaSnapshot(ProviderType.CODEX, null, null, null, NOW, NOW.minusSeconds(60), "plus", "codex",
                List.of(older.windows().getFirst(), secondary), null, 0, null, older.accountEvidence());
        final var latest = sample(0, 40);
        final var current = new QuotaSnapshot(ProviderType.CODEX, null, null, null, NOW, NOW, "plus", "codex",
                List.of(latest.windows().getFirst(), new QuotaWindow("secondary", 60, 300, NOW.plusSeconds(3600), "codex", NOW)),
                null, 0, null, latest.accountEvidence());
        final var enriched = forecasts.enrich(current, CONTEXT, List.of(entry(sample(-240, 20)), entry(older), entry(changedOtherWindow)), NOW);
        assertThat(enriched.windows().getFirst().forecast().sampleCount()).isEqualTo(3);
        assertThat(enriched.windows().getFirst().forecast().status()).isEqualTo(ESTIMATED);
        assertThat(enriched.windows().get(1).forecast().sampleCount()).isEqualTo(2);
        assertThat(enriched.windows().get(1).forecast().status()).isEqualTo(INSUFFICIENT_DATA);
    }

    // Validates a native plan change or explicit missing window blocks old archive samples even when that transition was never saved.
    @Test void shouldRespectUnsavedNativeCompatibilityBoundaries() throws Exception {
        final var file = directory.resolve("native.jsonl");
        for (final var barrier : List.of(nativeRow(-60, 35, "pro", false), nativeRow(-60, 35, "plus", true))) {
            Files.writeString(file, "{\"type\":\"session_meta\",\"payload\":{\"creator_account_id\":\"synthetic-account\"}}\n"
                    + nativeRow(-240, 20, "plus", false) + "\n" + nativeRow(-120, 30, "plus", false) + "\n"
                    + barrier + "\n" + nativeRow(0, 40, "plus", false) + "\n");
            final var current = new CodexQuotaReader(Clock.fixed(NOW, ZoneOffset.UTC)).read(List.of(file)).orElseThrow();
            assertThat(current.windows().getFirst().forecast().historyBoundary()).isEqualTo(NOW.minusSeconds(60));
            final var enriched = forecasts.enrich(current, CONTEXT, List.of(entry(sample(-240, 20)), entry(sample(-120, 30))), NOW);
            assertThat(enriched.windows().getFirst().forecast().status()).isEqualTo(INSUFFICIENT_DATA);
            assertThat(enriched.windows().getFirst().forecast().source()).isEqualTo(NATIVE_LOGS);
            assertThat(codec.mapper.writeValueAsString(current)).doesNotContain("historyBoundary", "synthetic-account");
        }
    }

    // Validates two native observations can reject a counter regression even when older archive samples would suggest growth.
    @Test void shouldPreserveSparseNativeCounterRejection() throws Exception {
        final var file = directory.resolve("regressing-native.jsonl");
        Files.writeString(file, "{\"type\":\"session_meta\",\"payload\":{\"creator_account_id\":\"synthetic-account\"}}\n"
                + nativeRow(-60, 45, "plus", false) + "\n" + nativeRow(0, 40, "plus", false) + "\n");
        final var current = new CodexQuotaReader(Clock.fixed(NOW, ZoneOffset.UTC)).read(List.of(file)).orElseThrow();
        assertThat(result(current, sample(-240, 20), sample(-120, 30)).status()).isEqualTo(DISCONTINUOUS);
        assertThat(result(current, sample(-240, 20), sample(-120, 30)).source()).isEqualTo(NATIVE_LOGS);
    }

    // Validates account, plan, scope, duration and reset reversions stop the suffix rather than reconnecting older matching groups.
    @Test void shouldStopAtInterveningContextChanges() {
        for (final var changed : List.of(
                sample(-60, 35, "other-account", "plus", "codex", NOW.plusSeconds(3600), 300),
                sample(-60, 35, "synthetic-account", "pro", "codex", NOW.plusSeconds(3600), 300),
                sample(-60, 35, "synthetic-account", "plus", "other-scope", NOW.plusSeconds(3600), 300),
                sample(-60, 35, "synthetic-account", "plus", "codex", NOW.plusSeconds(7200), 300),
                sample(-60, 35, "synthetic-account", "plus", "codex", NOW.plusSeconds(3600), 301),
                sample(-60, 35, null, "plus", "codex", NOW.plusSeconds(3600), 300))) {
            final var forecast = result(sample(0, 40), sample(-240, 20), sample(-120, 30), changed);
            assertThat(forecast.status()).isEqualTo(INSUFFICIENT_DATA);
            assertThat(forecast.source()).isEqualTo(NATIVE_LOGS);
        }
        final var legacy = entry(sample(-60, 35));
        final var unidentified = new QuotaHistoryEntry(legacy.id(), NOW, false, legacy.snapshot(), null);
        assertThat(forecasts.enrich(sample(0, 40), CONTEXT, List.of(entry(sample(-240, 20)), entry(sample(-120, 30)), unidentified), NOW)
                .windows().getFirst().forecast().source()).isEqualTo(NATIVE_LOGS);
    }

    // Validates gaps, regressing counters, equal-time conflicts and source rollback cannot create an actionable estimate.
    @Test void shouldRejectDiscontinuousArchiveEvidence() {
        assertThat(result(sample(0, 40), sample(-900, 20), sample(-120, 30)).status()).isEqualTo(DISCONTINUOUS);
        assertThat(result(sample(0, 40), sample(-240, 35), sample(-120, 30)).status()).isEqualTo(DISCONTINUOUS);
        assertThat(result(sample(0, 40), sample(-240, 20), sample(-120, 30), sample(-120, 31)).status()).isEqualTo(DISCONTINUOUS);
        assertThat(result(sample(0, 40), sample(60, 45)).status()).isEqualTo(DISCONTINUOUS);
        assertThat(result(sample(0, 40), sample(0, 39)).status()).isEqualTo(DISCONTINUOUS);
    }

    // Validates archive evidence cannot replace native stale/reset/exhausted/unsafe outcomes or an already valid native estimate.
    @Test void shouldPreserveNativeResultsAndOtherProviders() {
        for (final var status : QuotaForecast.Status.values()) {
            if (status == INSUFFICIENT_DATA) continue;
            final var base = sample(0, 40);
            final var original = new QuotaForecast(status, 1, NOW, NOW, null, null, null, null, NATIVE_LOGS);
            final var window = base.windows().getFirst();
            final var current = new QuotaSnapshot(base.provider(), null, null, null, NOW, NOW, "plus", "codex",
                    List.of(new QuotaWindow(window.name(), window.usedPercent(), window.windowMinutes(), window.resetAt(), window.limitId(), NOW, original, null)),
                    null, 0, null, base.accountEvidence());
            assertThat(result(current, sample(-240, 20), sample(-120, 30))).isSameAs(original);
        }
        final var unidentified = sample(0, 40, null, "plus", "codex", NOW.plusSeconds(3600), 300);
        assertThat(forecasts.enrich(unidentified, CONTEXT, List.of(entry(sample(-240, 20)), entry(sample(-120, 30))), NOW)).isSameAs(unidentified);
        final var kiro = QuotaHistoryTestData.kiro();
        assertThat(forecasts.enrich(kiro, CONTEXT, List.of(), NOW)).isSameAs(kiro);
    }

    // Validates the shared calculator still enforces minimum duration, no growth, reset-first and fresh source evidence.
    @Test void shouldReuseForecastEligibilityRules() {
        assertThat(result(sample(0, 40), sample(-60, 20), sample(-30, 30)).status()).isEqualTo(INSUFFICIENT_DATA);
        assertThat(result(sample(0, 40), sample(-240, 40), sample(-120, 40)).status()).isEqualTo(NO_GROWTH);
        assertThat(result(sample(0, 40), sample(-240, 39.98), sample(-120, 39.99)).status()).isEqualTo(RESET_FIRST);
        assertThat(result(sample(-301, 40), sample(-541, 20), sample(-421, 30)).status()).isEqualTo(STALE);
        assertThat(result(sample(0, 40), sample(-1900, 20), sample(-120, 30)).sampleCount()).isEqualTo(2);
    }

    // Validates the bounded calculation uses all retained records beyond the history UI's 100-row limit, capped at 256 distinct samples.
    @Test void shouldBoundDistinctSamplesIndependentlyOfDisplayLimit() {
        final var entries = new ArrayList<QuotaHistoryEntry>();
        for (int index = 0; index < 300; index++) entries.add(entry(sample(-300 + index, 20 + index / 20.0)));
        final var forecast = forecasts.enrich(sample(0, 35), CONTEXT, entries, NOW).windows().getFirst().forecast();
        assertThat(forecast.status()).isEqualTo(ESTIMATED);
        assertThat(forecast.sampleCount()).isEqualTo(256);
        assertThat(forecast.sampleFrom()).isEqualTo(NOW.minusSeconds(255));
    }

    // Validates service recreation restores the forecast from disk while archived snapshots remain prediction-free.
    @Test void shouldRestoreAfterRestartWithoutPersistingForecasts() throws Exception {
        history(CONTEXT).record(sample(-240, 20));
        history(CONTEXT).record(sample(-120, 30));
        final var current = sample(0, 40);
        final var source = new FakeLlmProviderAdapter(new UsageSnapshot(ProviderType.CODEX, null, null, null, NOW)) {
            @Override public Optional<QuotaSnapshot> getQuota() { return Optional.of(current); }
        };
        final var collected = new QuotaService(List.of(source), history(CONTEXT)).collect(ProviderType.CODEX).getFirst().snapshot();
        assertThat(collected.windows().getFirst().forecast().source()).isEqualTo(SAVED_HISTORY);
        assertThat(collected.windows().getFirst().forecast().status()).isEqualTo(ESTIMATED);
        assertThat(history(CONTEXT).read(ProviderType.CODEX, 20).observations()).allSatisfy(entry ->
                assertThat(entry.snapshot().windows().getFirst().forecast()).isNull());
        assertThat(Files.readString(directory.resolve("codex.json"))).doesNotContain("synthetic-account", "forecast", "NATIVE_LOGS", "SAVED_HISTORY");
        assertThat(history(QuotaHistoryCodec.hash("another-source")).withForecasts(current).windows().getFirst().forecast().source()).isEqualTo(NATIVE_LOGS);
        assertThat(QuotaHistoryService.disabled().withForecasts(current)).isSameAs(current);
    }

    // Validates damaged storage leaves the live native result intact and never resets or repairs the user's archive.
    @Test void shouldPreserveLiveResultWhenArchiveIsUnreadable() throws Exception {
        Files.writeString(directory.resolve("codex.json"), "SYNTHETIC_CORRUPTION");
        final var current = sample(0, 40);
        assertThat(history(CONTEXT).withForecasts(current)).isSameAs(current);
        assertThat(Files.readString(directory.resolve("codex.json"))).isEqualTo("SYNTHETIC_CORRUPTION");
    }
}
