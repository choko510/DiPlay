#!/usr/bin/env python3
"""Validate and publish machine-readable DSP emulator benchmark results."""

from __future__ import annotations

import csv
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path


EXPECTED_CASES = (
    "Gain",
    "PEQ15",
    "PEQ_Compressor_Limiter",
    "Bass_Stereo_MonoBass",
    "Multiband3",
    "DynamicEQ",
    "Convolver4k",
    "Convolver16k",
    "Convolver64k",
    "SeatOnly",
    "AdvancedMsOnly",
    "CabinOnly",
    "Harmonic2x",
    "Harmonic4x",
    "VirtualBass",
    "All4",
    "All4+Convolver64k",
    "LegacyBaseline",
)
SAMPLE_RATES = (44_100, 48_000)
CHANNEL_COUNTS = (1, 2)
MEASURED_RUNS = 3
METRICS = ("avg", "p50", "p95", "p99", "max", "count")


def parse_fields(line: str) -> dict[str, str]:
    return dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)=([^\s]+)", line))


def parse_metric(value: str | None) -> dict[str, float | int] | None:
    if value is None or value == "not-applicable":
        return None
    metric: dict[str, float | int] = {}
    for item in value.split(","):
        key, raw = item.split(":", 1)
        metric[key] = int(raw) if key in {"p50", "p95", "p99", "max", "count"} else float(raw)
    missing = set(METRICS) - metric.keys()
    if missing:
        raise ValueError(f"benchmark metric {value!r} is missing {sorted(missing)}")
    return metric


def parse_log(log_path: Path) -> tuple[list[dict], list[dict]]:
    records = []
    memory_records = []
    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        marker = "DSP_BENCHMARK "
        memory_marker = "DSP_BENCHMARK_MEMORY "
        if marker in line:
            fields = parse_fields(line[line.index(marker) + len(marker) :])
            if "case" not in fields:
                continue
            record = {
                "case": fields["case"],
                "sampleRate": int(fields["sampleRate"]),
                "channels": int(fields["channels"]),
                "run": int(fields["run"]),
                "blocks": int(fields["blocks"]),
                "latencyPreservingBypass": fields["latencyPreservingBypass"] == "true",
                "nativeProcessUs": parse_metric(fields.get("nativeProcessUs")),
                "pipelineProcessUs": parse_metric(fields.get("pipelineProcessUs")),
                "productionProcessUs": parse_metric(fields.get("productionProcessUs")),
                "steadyStateAllocations": int(fields["steadyStateAllocations"]),
                "nativeHeapPeakBytes": int(fields["nativeHeapPeakBytes"]),
                "nativeHeapDeltaBytes": int(fields["nativeHeapDeltaBytes"]),
            }
            records.append(record)
        if memory_marker in line:
            fields = parse_fields(line[line.index(memory_marker) + len(memory_marker) :])
            if "graphCount" in fields:
                memory_records.append(
                    {
                        key: int(fields[key])
                        for key in (
                            "sampleRate",
                            "channels",
                            "graphCount",
                            "fallbackLatencyFrames",
                            "nativeHeapBytes",
                            "nativeHeapDeltaBytes",
                            "totalPssKb",
                            "irFrames",
                        )
                    }
                    | {key: fields[key] for key in ("active", "candidate", "rollback")}
                )
    return records, memory_records


def summarize(records: list[dict]) -> list[dict]:
    grouped: dict[tuple[str, int, int], list[dict]] = defaultdict(list)
    for record in records:
        grouped[(record["case"], record["sampleRate"], record["channels"])].append(record)

    summaries = []
    for (case, sample_rate, channels), runs in sorted(grouped.items(), key=lambda entry: (entry[0][1], entry[0][2], EXPECTED_CASES.index(entry[0][0]))):
        summary = {"case": case, "sampleRate": sample_rate, "channels": channels}
        for stage in ("nativeProcessUs", "pipelineProcessUs", "productionProcessUs"):
            stage_runs = [run[stage] for run in runs if run[stage] is not None]
            for metric_name in METRICS:
                values = [run[metric_name] for run in stage_runs]
                if not values:
                    summary[f"{stage}_{metric_name}"] = None
                elif metric_name == "max":
                    summary[f"{stage}_{metric_name}"] = max(values)
                else:
                    summary[f"{stage}_{metric_name}"] = statistics.median(values)
        summary["steadyStateAllocations"] = max(run["steadyStateAllocations"] for run in runs)
        summary["nativeHeapPeakBytes"] = max(run["nativeHeapPeakBytes"] for run in runs)
        summary["nativeHeapDeltaBytes"] = max(run["nativeHeapDeltaBytes"] for run in runs)
        summaries.append(summary)
    return summaries


def validate(records: list[dict], memory_records: list[dict]) -> None:
    expected_count = len(EXPECTED_CASES) * len(SAMPLE_RATES) * len(CHANNEL_COUNTS) * MEASURED_RUNS
    if len(records) != expected_count:
        raise ValueError(f"expected {expected_count} DSP_BENCHMARK records, found {len(records)}")
    keys = [(r["case"], r["sampleRate"], r["channels"], r["run"]) for r in records]
    if len(keys) != len(set(keys)):
        raise ValueError("duplicate benchmark case/sample-rate/channel/run record")
    expected_keys = {
        (case, rate, channels, run)
        for case in EXPECTED_CASES
        for rate in SAMPLE_RATES
        for channels in CHANNEL_COUNTS
        for run in range(1, MEASURED_RUNS + 1)
    }
    missing = expected_keys - set(keys)
    unexpected = set(keys) - expected_keys
    if missing or unexpected:
        raise ValueError(f"benchmark matrix mismatch; missing={sorted(missing)}, unexpected={sorted(unexpected)}")
    nonzero = [r for r in records if r["steadyStateAllocations"] != 0]
    if nonzero:
        raise ValueError(f"steady-state Kotlin allocation count was nonzero: {nonzero[:3]}")
    expected_memory = [{
        "sampleRate": 48_000,
        "channels": 2,
        "graphCount": 3,
        "active": "Convolver64k",
        "candidate": "All4+Convolver64k",
        "rollback": "All4+Convolver64k",
        "fallbackLatencyFrames": memory_records[0]["fallbackLatencyFrames"] if memory_records else -1,
        "nativeHeapBytes": memory_records[0]["nativeHeapBytes"] if memory_records else -1,
        "nativeHeapDeltaBytes": memory_records[0]["nativeHeapDeltaBytes"] if memory_records else -1,
        "totalPssKb": memory_records[0]["totalPssKb"] if memory_records else -1,
        "irFrames": 65_536,
    }]
    if len(memory_records) != 1 or memory_records != expected_memory:
        raise ValueError(f"expected one three-graph memory sample, found {memory_records}")


def write_csv(path: Path, records: list[dict], summaries: list[dict]) -> None:
    raw_fields = [
        "case",
        "sampleRate",
        "channels",
        "run",
        "blocks",
        "latencyPreservingBypass",
        "steadyStateAllocations",
        "nativeHeapPeakBytes",
        "nativeHeapDeltaBytes",
    ]
    for stage in ("nativeProcessUs", "pipelineProcessUs", "productionProcessUs"):
        raw_fields.extend(f"{stage}_{metric}" for metric in METRICS)
    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=raw_fields)
        writer.writeheader()
        for record in records:
            row = {key: value for key, value in record.items() if key not in {
                "nativeProcessUs", "pipelineProcessUs", "productionProcessUs",
            }}
            for stage in ("nativeProcessUs", "pipelineProcessUs", "productionProcessUs"):
                metric = record[stage] or {}
                row.update({f"{stage}_{key}": metric.get(key) for key in METRICS})
            writer.writerow(row)

    summary_fields = list(summaries[0])
    with path.with_name("summary.csv").open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=summary_fields)
        writer.writeheader()
        writer.writerows(summaries)


def format_metric(summary: dict, stage: str) -> str:
    p50 = summary.get(f"{stage}_p50")
    if p50 is None:
        return "—"
    return "/".join(str(round(summary[f"{stage}_{metric}"])) for metric in ("p50", "p95", "p99", "max"))


def write_markdown(path: Path, summaries: list[dict], memory: dict) -> None:
    lines = [
        "# DSP Emulator Benchmark Results",
        "",
        "API 36 x86_64 emulator; 512-frame blocks; three 256-block measured runs per configuration after warm-up.",
        "P50/p95/p99 values are the median of each run's histogram percentiles; max is the worst observed sample, in microseconds. Production includes the latency-preserving delayed-dry path when the graph has latency. Kotlin steady-state allocation counts are zero for every case.",
        "",
        "| Case | Rate | Channels | Native p50/p95/p99/max | Pipeline p50/p95/p99/max | Production p50/p95/p99/max | Heap peak (bytes) |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for summary in summaries:
        lines.append(
            "| {case} | {sampleRate} | {channels} | {native} | {pipeline} | {production} | {heap} |".format(
                case=summary["case"],
                sampleRate=summary["sampleRate"],
                channels=summary["channels"],
                native=format_metric(summary, "nativeProcessUs"),
                pipeline=format_metric(summary, "pipelineProcessUs"),
                production=format_metric(summary, "productionProcessUs"),
                heap=summary["nativeHeapPeakBytes"],
            ),
        )
    lines.extend(
        [
            "",
            "## Graph-swap memory sample",
            "",
            f"With an active Convolver64k graph, an All4+Convolver64k candidate, its rollback graph, and a latency-aligned fallback alive together: native heap {memory['nativeHeapBytes']} bytes (delta {memory['nativeHeapDeltaBytes']} bytes), process PSS {memory['totalPssKb']} KiB, fallback latency {memory['fallbackLatencyFrames']} frames.",
            "",
            "The heap values are emulator measurements for that live graph set. They do not represent TS10 memory use.",
            "",
        ],
    )
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 3:
        print(f"usage: {Path(sys.argv[0]).name} <logcat.txt> <output-directory>", file=sys.stderr)
        return 2
    log_path = Path(sys.argv[1])
    output_dir = Path(sys.argv[2])
    if not log_path.is_file():
        print(f"missing benchmark log: {log_path}", file=sys.stderr)
        return 1
    output_dir.mkdir(parents=True, exist_ok=True)
    try:
        records, memory_records = parse_log(log_path)
        validate(records, memory_records)
        summaries = summarize(records)
        (output_dir / "results.json").write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "environment": "API 36 x86_64 emulator",
                    "sampleRates": SAMPLE_RATES,
                    "channelCounts": CHANNEL_COUNTS,
                    "expectedCases": EXPECTED_CASES,
                    "measuredRuns": MEASURED_RUNS,
                    "records": records,
                    "summary": summaries,
                    "memory": memory_records,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        write_csv(output_dir / "results.csv", records, summaries)
        write_markdown(output_dir / "results.md", summaries, memory_records[0])
    except (KeyError, ValueError, OSError) as error:
        print(f"DSP benchmark artifact validation failed: {error}", file=sys.stderr)
        return 1
    print(f"validated {len(records)} DSP benchmark records and wrote artifacts to {output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
