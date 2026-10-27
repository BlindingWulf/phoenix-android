package org.phoenix.runtimeprobe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.widget.TextView;
import org.json.JSONObject;
import org.phoenix.runtime.RuntimeLayout;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

public final class ProbeActivity extends Activity {
    private TextView text;
    private void line(String value) { Log.i("PhoenixProbe", value); runOnUiThread(() -> text.append(value + "\n")); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        text = new TextView(this); text.setText("Running actual embedded Java 25 probe\n"); setContentView(text);
        new Thread(() -> {
            try {
                String abi = Build.SUPPORTED_ABIS[0];
                String assetBase = "runtime/" + abi + "/";
                JSONObject manifest;
                try (var input = getAssets().open(assetBase + "runtime-manifest.json")) { manifest = new JSONObject(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
                Path javaHome = getFilesDir().toPath().resolve("jre25");
                var dataHashes = new HashMap<String,String>();
                var data = manifest.getJSONArray("data");
                for (int i = 0; i < data.length(); i++) { var entry = data.getJSONObject(i); dataHashes.put(entry.getString("path"), entry.getString("sha256")); }
                try (var input = getAssets().open(assetBase + "jre-data.zip")) { RuntimeLayout.unpackData(input, javaHome, dataHashes); }
                var nativeEntries = new ArrayList<RuntimeLayout.NativeEntry>();
                var natives = manifest.getJSONArray("native");
                for (int i = 0; i < natives.length(); i++) { var entry = natives.getJSONObject(i); nativeEntries.add(new RuntimeLayout.NativeEntry(entry.getString("runtimePath"), entry.getString("installedName"), entry.getString("sha256"))); }
                Path nativeDir = Path.of(getApplicationInfo().nativeLibraryDir);
                RuntimeLayout.linkNative(javaHome, nativeDir, nativeEntries);
                Path jar = getFilesDir().toPath().resolve("phoenix-runtime.jar");
                try (var input = getAssets().open("runtime/phoenix-runtime.jar")) { Files.copy(input, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                var command = new ArrayList<String>();
                command.add(nativeDir.resolve("libphoenixjvm.so").toString()); command.add(nativeDir.toString()); command.add(javaHome.toString());
                if (getIntent().getBooleanExtra("agent", false)) {
                    Path agent = getFilesDir().toPath().resolve("phoenix-metrics.jar");
                    try (var input = getAssets().open("runtime/phoenix-metrics.jar")) { Files.copy(input, agent, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                    command.add("-javaagent:" + agent);
                }
                java.util.Collections.addAll(command,
                        "-Xms32m", "-Xmx128m", "-Djava.io.tmpdir=" + getCacheDir(), "-Djava.class.path=" + jar,
                        "--", "org.phoenix.runtime.RuntimeProbe", getFilesDir().toPath().resolve("probe").toString());
                var process = new ProcessBuilder(command);
                process.directory(getFilesDir()); process.redirectErrorStream(true);
                process.environment().put("LD_LIBRARY_PATH", nativeDir.toString());
                process.environment().put("JAVA_HOME", javaHome.toString());
                process.environment().put("HOME", getFilesDir().toString());
                process.environment().put("TMPDIR", getCacheDir().toString());
                Process running = process.start();
                try (var output = new BufferedReader(new InputStreamReader(running.getInputStream()))) { for (String next; (next = output.readLine()) != null;) line(next); }
                line("PHOENIX_NATIVE_EXIT " + running.waitFor());
            } catch (Throwable error) { line("PHOENIX_ANDROID_PROBE_ERROR " + Log.getStackTraceString(error)); }
        }, "phoenix-probe").start();
    }
}
