#!/usr/bin/env python3
"""Build and run the local Java collection diagnostic; stdout contains technical JSONL only."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MAIN = "com.llmeter.personal.provider.codex.service.diagnostic.CodexCollectionBenchmark"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Read-only local sessions directory; omit for generated synthetic data")
    parser.add_argument("--scenario", choices=("static", "append", "partial-write"), default="static")
    for name in ("files", "events", "pending", "payload-bytes"):
        parser.add_argument("--" + name, type=int, default=None)
    parser.add_argument("--warmup", type=int, default=2)
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--interval-ms", type=int, default=0)
    parser.add_argument("--from", dest="from_date", default="2026-10-01")
    parser.add_argument("--to", dest="to_date", default="2026-10-31")
    parser.add_argument("--zone", default="UTC")
    parser.add_argument("--heap-mb", type=int, default=512)
    parser.add_argument("--skip-build", action="store_true", help="Reuse existing compiled tools and JAR; rerun build after source edits")
    args = parser.parse_args()
    if not 128 <= args.heap_mb <= 4096:
        parser.error("heap-mb must be between 128 and 4096")
    if args.source and (args.scenario != "static" or any(getattr(args, key) is not None for key in ("files", "events", "pending", "payload_bytes"))):
        parser.error("local source mode is read-only and does not accept synthetic workload options")
    if not args.skip_build:
        built = subprocess.run(["mvn", "-q", "-DskipTests", "package"], cwd=ROOT, stdout=sys.stderr, stderr=sys.stderr)
        if built.returncode:
            return built.returncode
    jars = sorted((ROOT / "target").glob("llm-meter-personal-*.jar"), key=lambda path: path.stat().st_mtime, reverse=True)
    jars = [path for path in jars if not path.name.endswith(("-sources.jar", "-javadoc.jar"))]
    if not jars or not (ROOT / "target/test-classes" / (MAIN.replace(".", "/") + ".class")).is_file():
        sys.stderr.write("Benchmark build artifacts are missing. Run without --skip-build.\n")
        return 1
    with zipfile.ZipFile(jars[0]) as jar:
        if "org/springframework/boot/loader/launch/PropertiesLauncher.class" not in jar.namelist():
            sys.stderr.write("The executable backend JAR is required. Run without --skip-build.\n")
            return 1
    java = os.environ.get("LLM_METER_JAVA") or (str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java")
    command = [java, "-Xms128m", f"-Xmx{args.heap_mb}m", "-XX:+UseG1GC", f"-Dloader.main={MAIN}",
               f"-Dloader.path={ROOT / 'target/test-classes'}", "-cp", str(jars[0]),
               "org.springframework.boot.loader.launch.PropertiesLauncher"]
    for name, value in (("scenario", args.scenario), ("warmup", args.warmup), ("iterations", args.iterations),
                        ("interval-ms", args.interval_ms), ("from", args.from_date), ("to", args.to_date), ("zone", args.zone)):
        command.extend(("--" + name, str(value)))
    if args.source:
        command.extend(("--source", str(args.source.resolve())))
    else:
        for key in ("files", "events", "pending", "payload_bytes"):
            value = getattr(args, key)
            if value is not None:
                command.extend(("--" + key.replace("_", "-"), str(value)))
    return subprocess.run(command, cwd=ROOT).returncode


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, zipfile.BadZipFile):
        sys.stderr.write("Benchmark could not start. Check Java 21, Maven, and build artifacts.\n")
        sys.exit(1)
