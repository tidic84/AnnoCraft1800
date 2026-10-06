"""Launch two isolated development clients from Gradle's exported argument list (no shell)."""
from pathlib import Path
import json
import os
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
config = json.loads((ROOT / ".tools/client-launch.json").read_text(encoding="utf-8-sig"))
ready = ROOT / "run-network-smoke/network-ready.txt"
deadline = time.monotonic() + 180
started = time.time()
while not ready.exists() or ready.stat().st_mtime < started - 30:
    if time.monotonic() > deadline:
        raise SystemExit("Network server did not become ready")
    time.sleep(1)
processes = []
logs = []
try:
    for role in ("A", "B"):
        folder = ROOT / ("run-client-network-" + role.lower())
        folder.mkdir(exist_ok=True)
        result = folder / "smoke-result.txt"
        if result.exists():
            result.unlink()
        # Explicitly test the French UI with a known viewport and render distance.
        (folder / "options.txt").write_text("version:3465\nonboardAccessibility:false\nlang:fr_fr\nguiScale:3\nrenderDistance:8\nsimulationDistance:5\npauseOnLostFocus:false\n", encoding="utf-8")
        command = list(config["command"])
        command.insert(1, "-Dannocraft1800.networkRole=" + role)
        if not any(arg.startswith("-Dannocraft1800.clientSmoke=") for arg in command):
            command.insert(1, "-Dannocraft1800.clientSmoke=true")
        command += ["--username", "AnnoTester" + role]
        environment = os.environ.copy()
        environment.update(config["environment"])
        log = (folder / "launch.log").open("w", encoding="utf-8")
        logs.append(log)
        processes.append(subprocess.Popen(command, cwd=folder, env=environment, stdout=log, stderr=subprocess.STDOUT))
    deadline = time.monotonic() + 300
    while any(p.poll() is None for p in processes) and time.monotonic() < deadline:
        time.sleep(1)
    for role, process in zip(("A", "B"), processes):
        result = ROOT / ("run-client-network-" + role.lower()) / "smoke-result.txt"
        if process.poll() != 0 or not result.exists() or not result.read_text().startswith("PASS"):
            raise SystemExit("Client " + role + " failed; inspect its launch.log and smoke-result.txt")
    server_result = ROOT / "run-network-smoke/network-result.txt"
    if not server_result.exists() or not server_result.read_text().startswith("PASS"):
        raise SystemExit("Server network assertions did not complete")
    print("PASS: both graphical clients and the loopback server completed the network smoke test.")
finally:
    for process in processes:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=20)
    for log in logs:
        log.close()
