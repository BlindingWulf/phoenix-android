"""Linux host tests for the exact launcher lifecycle/whitelisted exec logic.

This builds the same original C source against public Linux JNI headers and a
small local daemon fixture. It cannot certify Android execution or Playit traffic.
"""
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request

root = Path(__file__).resolve().parents[2]
source = root / "android-stage10a/runtime/phoenix_jvm.c"
with tempfile.TemporaryDirectory(prefix="phoenix-native-lifecycle-") as scratch:
    directory = Path(scratch)
    headers = directory / "headers"; headers.mkdir()
    for name, url in {
        "jni.h": "https://raw.githubusercontent.com/openjdk/jdk25u/29d0b2b336e0a936436a5799d4bbdf4123155c09/src/java.base/share/native/include/jni.h",
        "jni_md.h": "https://raw.githubusercontent.com/openjdk/jdk25u/29d0b2b336e0a936436a5799d4bbdf4123155c09/src/java.base/unix/native/include/jni_md.h",
    }.items(): urllib.request.urlretrieve(url, headers / name)
    native = directory / "native"; native.mkdir()
    host = native / "libphoenixjvm.so"
    subprocess.run(["gcc", "-O2", "-Wall", "-Wextra", "-Werror", "-I", str(headers), str(source), "-ldl", "-Wl,--export-dynamic", "-o", str(host)], check=True)
    fixture = directory / "daemon.c"
    fixture.write_text('''#include <stdio.h>
#include <signal.h>
#include <unistd.h>
#include <sys/prctl.h>
int main(void) { int configured=0; if(prctl(PR_GET_PDEATHSIG,&configured)!=0 || configured!=SIGTERM)return 71;
printf("FIXTURE_READY pid=%ld signal=%d\\n",(long)getpid(),configured); fflush(stdout); for(;;)pause(); }
''')
    agent = native / "libplayitagent.so"
    subprocess.run(["gcc", "-Wall", "-Wextra", "-Werror", str(fixture), "-o", str(agent)], check=True)
    agent.chmod(0o555)
    private = directory / "private"; private.mkdir(mode=0o700)
    secret = private / "agent-key"; secret.write_text("test-fixture-not-a-provider-secret"); secret.chmod(0o600)
    socket = private / "playitd.sock"
    command = [str(host), str(native), "--playit", "--secret-path", str(secret), "--socket-path", str(socket)]
    def rejected(arguments):
        result = subprocess.run(arguments, capture_output=True, text=True, timeout=5)
        if result.returncode != 70 or "PHOENIX_RUNTIME_ERROR" not in result.stderr:
            raise AssertionError((arguments, result.returncode, result.stderr))
    rejected(command[:-1])
    rejected([str(host), str(private), *command[2:]])
    rejected([*command[:-1], str(private / "arbitrary.sock")])
    secret.chmod(0o644); rejected(command); secret.chmod(0o600)
    link = private / "secret-link"; link.symlink_to(secret)
    rejected([*command[:4], str(link), *command[5:]])
    agent.chmod(0o755); rejected(command); agent.chmod(0o555)
    print("PASS whitelist, owning native-dir, secret mode/symlink, socket name, immutable agent checks")
    parent_script = directory / "parent.py"
    parent_script.write_text('''import subprocess,sys
p=subprocess.Popen(sys.argv[1:],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
for line in p.stdout:
 print(line.strip(),flush=True)
''')
    parent = subprocess.Popen([sys.executable, str(parent_script), *command], stdout=subprocess.PIPE, text=True)
    child_pid = None
    for _ in range(5):
        line = parent.stdout.readline().strip()
        print(line)
        if line.startswith("FIXTURE_READY"):
            child_pid = int(line.split("pid=")[1].split()[0]); break
    if child_pid is None: parent.kill(); raise AssertionError("Child fixture failed to reach ready")
    parent.kill(); parent.wait(timeout=5)
    dead = False
    for _ in range(100):
        status = Path(f"/proc/{child_pid}/stat")
        if not status.exists() or status.read_text().split(")",1)[1].strip().startswith("Z"):
            dead = True; break
        time.sleep(0.01)
    if not dead:
        os.kill(child_pid, signal.SIGKILL)
        raise AssertionError("Child continued after parent process death")
    print("PASS SIGTERM retained through execv and child stopped after creating parent's SIGKILL")
