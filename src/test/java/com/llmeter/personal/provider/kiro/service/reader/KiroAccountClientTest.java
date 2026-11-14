package com.llmeter.personal.provider.kiro.service.reader;

import com.llmeter.personal.provider.exception.ProviderCollectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KiroAccountClientTest {
    @TempDir Path root;
    // Validates the connection only initializes and reads account usage, declining client-side operations.
    @Test
    void shouldReadAccountWithoutCreatingSessionOrPrompt() throws IOException {
        final var script = executable("""
                import json,sys
                assert sys.argv[1:]==['acp','--agent-engine','v3','--auth-method','cli']
                first=json.loads(input());assert first['method']=='initialize'
                print(json.dumps({'jsonrpc':'2.0','id':1,'result':{'protocolVersion':1}}),flush=True)
                second=json.loads(input());assert second['method']=='_kiro/account/getUsage'
                print(json.dumps({'jsonrpc':'2.0','id':'request','method':'fs/read_text_file','params':{}}),flush=True)
                denied=json.loads(input());assert denied['error']['code']==-32601
                print('{"jsonrpc":"2.0","id":2,"result":{"success":true,"data":{"used":0.123456789012345678}}}',flush=True)
                """);
        assertThat(new String(new KiroAccountClient(script).read(), StandardCharsets.UTF_8))
                .contains("0.123456789012345678").doesNotContain("sessionId");
    }
    // Validates hung CLI subprocesses are terminated within the account query deadline.
    @Test
    void shouldBoundRuntime() throws IOException {
        final var script = executable("import time\ntime.sleep(60)\n");
        final long start = System.nanoTime();
        assertThatThrownBy(() -> new KiroAccountClient(script, 150).read()).isInstanceOf(ProviderCollectionException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3_000);
    }
    // Validates corrupt output, oversized responses and authentication errors remain generic failures.
    @Test
    void shouldRejectInvalidOrExcessiveResponses() throws IOException {
        for (final var body : new String[]{"print('not json',flush=True)", "print('x'*2097153,flush=True)",
                "print('{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"PRIVATE_DATA\"}}',flush=True)"}) {
            final var script = executable(body);
            assertThatThrownBy(() -> new KiroAccountClient(script).read()).hasMessage("Kiro account query unavailable");
        }
    }
    // Validates missing CLI installs never trigger login or fallback to an unrelated executable.
    @Test
    void shouldHandleMissingExecutable() {
        assertThatThrownBy(() -> new KiroAccountClient(root.resolve("missing")).read()).hasMessage("Kiro account query unavailable");
    }
    private Path executable(final String body) throws IOException {
        final var file = Files.createTempFile(root, "synthetic-kiro-", ".py");
        Files.writeString(file, "#!/usr/bin/python3\n" + body);
        assertThat(file.toFile().setExecutable(true, true)).isTrue();
        return file;
    }
}
