#!/usr/bin/env python3
"""Safely inventory promotion ZIP inputs against the 2017 specification (stdlib)."""
import argparse
import csv
import hashlib
import json
import math
import re
import stat
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parent


def natural_key(value):
    return [int(p) if p.isdigit() else p.casefold() for p in re.split(r"(\d+)", str(value))]


def write_csv(path, rows, fields=None, delimiter=","):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields or list(rows[0]), delimiter=delimiter)
        writer.writeheader()
        writer.writerows(rows)


def inspect_input(path):
    errors = []
    result = {}

    def require(condition, message):
        if not condition and message not in errors and len(errors) < 24:
            errors.append(message)

    try:
        content = path.read_bytes()
        text = content.decode("ascii")
        lines = iter(text.splitlines())

        def line(count=None):
            values = next(lines).split()
            if count is not None and len(values) != count:
                raise ValueError(f"Expected {count} integers on a line, got {len(values)}")
            if any(not re.fullmatch(r"[0-9]+", v) for v in values):
                raise ValueError("Non-natural integer token")
            return list(map(int, values))

        v, e, r, c, x = line(5)
        result.update(videos=v, endpoints=e, request_descriptions=r, caches=c, capacity=x)
        if not (1 <= v <= 10000 and 1 <= e <= 1000 and 1 <= r <= 1000000
                and 1 <= c <= 1000 and 1 <= x <= 500000):
            raise ValueError("Dimensions outside official ranges")
        sizes = line(v)
        require(all(1 <= s <= 1000 for s in sizes), "Video size outside [1,1000]")
        degrees, dc_latencies, link_latencies = [], [], []
        for _ in range(e):
            dc, degree = line(2)
            require(2 <= dc <= 4000, "Datacenter latency outside [2,4000]")
            if not 0 <= degree <= c:
                raise ValueError("Connection count outside [0,C]")
            seen = set()
            degrees.append(degree)
            dc_latencies.append(dc)
            for _ in range(degree):
                cache, latency = line(2)
                require(0 <= cache < c, "Cache ID outside range")
                require(cache not in seen, "Duplicate cache link within endpoint")
                seen.add(cache)
                require(1 <= latency <= 500 and latency < dc, "Cache latency outside [1,min(500,DC-1)]")
                link_latencies.append(latency)
        popularity = [0] * v
        unique = set()
        total, contributions, max_product, max_count, over_limit = 0, 0, 0, 0, 0
        for _ in range(r):
            video, endpoint, count = line(3)
            require(0 <= video < v and 0 <= endpoint < e, "Request index outside range")
            require(1 <= count <= 10000, "Request count outside [1,10000]")
            max_count = max(max_count, count)
            over_limit += not 1 <= count <= 10000
            if not (0 <= video < v and 0 <= endpoint < e):
                continue
            total += count
            popularity[video] += count
            max_product = max(max_product, count * dc_latencies[endpoint])
            pair = (video, endpoint)
            if pair not in unique:
                unique.add(pair)
                contributions += degrees[endpoint]
        require(not any(s.strip() for s in lines), "Unexpected trailing data")
        # The supplied, unchanged C++ judge uses int totalRequest and int per-record products.
        judge_safe = total <= 2147483647 and max_product <= 2147483647
        mean_size = sum(sizes) / v
        result.update(total_requests=total, cache_connections=sum(degrees), unique_demands=len(unique),
                      duplicate_request_rows=r-len(unique), sparse_contributions=contributions,
                      candidate_pairs_upper=v*c, judge_integer_safe=judge_safe,
                      max_request_count=max_count, out_of_range_request_rows=over_limit,
                      average_degree=round(sum(degrees)/e, 6),
                      request_sparsity=round(len(unique)/(v*e), 8),
                      cache_capacity_ratio=round(x/sum(sizes), 8),
                      video_size_cv=round(math.sqrt(sum((s-mean_size)**2 for s in sizes)/v)/mean_size, 6),
                      demand_top_10_share=round(sum(sorted(popularity, reverse=True)[:10])/total, 8) if total else 0,
                      dc_latency_min=min(dc_latencies), dc_latency_max=max(dc_latencies),
                      cache_latency_min=min(link_latencies, default=0), cache_latency_max=max(link_latencies, default=0),
                      line_endings="CRLF" if b"\r\n" in content else "LF")
    except (ValueError, UnicodeError, StopIteration) as error:
        errors.append(str(error) or "Unexpected end of file")
    return result, errors


def inventory(archive, target, report):
    target.mkdir(parents=True, exist_ok=True)
    base = target.resolve()
    rows, hashes, destinations = [], {}, set()
    with zipfile.ZipFile(archive) as zipped:
        if sum(item.file_size for item in zipped.infolist()) > 512 * 1024 * 1024:
            raise ValueError("Archive exceeds 512 MiB extraction limit")
        # Validate ALL entries before writing ANY entry. Reject Windows aliases/ADS/symlinks.
        for item in zipped.infolist():
            name = item.filename.replace("\\", "/")
            parts = PurePosixPath(name).parts
            reserved = {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)),
                        *(f"LPT{i}" for i in range(1, 10))}
            if (not parts or name.startswith("/") or any(p in (".", "..") or ":" in p
                    or p.endswith((" ", ".")) or p.split(".")[0].upper() in reserved for p in parts)
                    or stat.S_ISLNK(item.external_attr >> 16) or item.file_size > 128*1024*1024):
                raise ValueError(f"Unsafe ZIP entry: {item.filename}")
            destination = base.joinpath(*parts).resolve()
            if not destination.is_relative_to(base) or str(destination).casefold() in destinations:
                raise ValueError(f"Escaping or aliased ZIP entry: {item.filename}")
            destinations.add(str(destination).casefold())
        for item in sorted(zipped.infolist(), key=lambda i: natural_key(i.filename)):
            if item.is_dir():
                continue
            data = zipped.read(item)  # Includes ZIP CRC verification.
            digest = hashlib.sha256(data).hexdigest()
            name = item.filename.replace("\\", "/")
            destination = base.joinpath(*PurePosixPath(name).parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists() and destination.read_bytes() != data:
                raise ValueError(f"Refusing to overwrite extracted input: {name}")
            destination.write_bytes(data)
            row = dict(order=len(rows)+1, filename=PurePosixPath(name).name, relative_path=name,
                       sha256=digest, file_bytes=len(data), status="unsupported", duplicate_of="", issues="")
            if destination.suffix.casefold() == ".in":
                values, errors = inspect_input(destination)
                row.update(values)
                row["status"] = "invalid" if errors else "valid"
                row["issues"] = "; ".join(errors)
                if digest in hashes:
                    row["duplicate_of"] = hashes[digest]
                    row["status"] = "duplicate" if not errors else "invalid_duplicate"
                else:
                    hashes[digest] = name
            rows.append(row)
    fields = list(dict.fromkeys(key for row in rows for key in row))
    report.mkdir(parents=True, exist_ok=True)
    write_csv(report / "instance-inventory.csv", rows, fields)
    feature_keys = [k for k in fields if k not in ("issues", "duplicate_of")]
    write_csv(report / "instance-features.csv", [{k: r.get(k, "") for k in feature_keys} for r in rows], feature_keys)
    manifest = {"archive_name": archive.name, "archive_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                "ordering": "natural case-insensitive relative filename", "file_count": len(rows),
                "validation": "official numeric ranges and line structure; CRLF accepted as portable whitespace",
                "extraction_directory": target.as_posix()}
    (report / "archive-manifest.json").write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8")
    for row in rows:
        print(row["order"], row["relative_path"], row["status"], row.get("videos", ""),
              row.get("caches", ""), row.get("capacity", ""), row.get("total_requests", ""), row["issues"], flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--target", type=Path, default=Path("tmp/promo-inputs"))
    parser.add_argument("--report", type=Path, default=Path("experiments/promo-benchmark"))
    args = parser.parse_args()
    archive = args.archive
    if archive is None:
        downloads = Path.home() / "Downloads"
        candidates = sorted(downloads.glob("*promo*.zip"), key=natural_key)
        exact = downloads / "instances_promo.zip"
        if exact.exists():
            archive = exact
        elif len(candidates) == 1:
            archive = candidates[0]
        else:
            parser.error("Supply --archive: no unique promotion archive found in Downloads")
    inventory(archive, args.target, args.report)


if __name__ == "__main__":
    main()
