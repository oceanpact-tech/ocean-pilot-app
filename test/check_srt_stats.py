#!/usr/bin/env python3
import argparse
import json
import statistics
import subprocess
import time
import urllib.request

# use: ./check_srt_stats.py -d time(s) -p drone_name

def get_mediamtx_srt_stats(api_url: str, path: str) -> dict | None:
    try:
        with urllib.request.urlopen(f"{api_url}/v3/srtconns/list", timeout=3) as resp:
            data = json.loads(resp.read().decode())
        items = data.get("items", [])
        # Prioriza a conexão que está publicando no path informado
        for item in items:
            if item.get("path") == path and item.get("state") == "publish":
                return item
        return items[0] if items else None
    except Exception as e:
        print(f"[AVISO] Falha ao consultar API do MediaMTX: {e}")
        return None


def measure_stream(rtsp_url: str, duration: int) -> dict:
    cmd = [
        "ffprobe",
        "-v", "error",
        "-select_streams", "v:0",
        "-show_entries", "frame=key_frame,best_effort_timestamp_time,width,height",
        "-of", "compact=p=0",
        rtsp_url,
    ]

    proc = subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )

    timestamps = []
    keyframe_ts = []
    resolution = "N/A"
    start_time = None

    try:
        for line in proc.stdout:
            fields = dict(
                item.split("=", 1) for item in line.strip().split("|") if "=" in item
            )
            ts_str = fields.get("best_effort_timestamp_time")
            if not ts_str or ts_str == "N/A":
                continue

            ts = float(ts_str)
            if start_time is None:
                start_time = time.monotonic()

            if resolution == "N/A" and "width" in fields and "height" in fields:
                resolution = f"{fields['width']}x{fields['height']}"

            timestamps.append(ts)
            if fields.get("key_frame") == "1":
                keyframe_ts.append(ts)

            if time.monotonic() - start_time >= duration:
                break
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            proc.kill()

    fps = None
    if len(timestamps) >= 2 and (timestamps[-1] - timestamps[0]) > 0:
        fps = (len(timestamps) - 1) / (timestamps[-1] - timestamps[0])

    kf_deltas = [t2 - t1 for t1, t2 in zip(keyframe_ts, keyframe_ts[1:])]
    kf_avg = statistics.mean(kf_deltas) if kf_deltas else None

    return {
        "resolution": resolution,
        "fps": fps,
        "frames": len(timestamps),
        "kf_avg": kf_avg,
        "kf_count": len(keyframe_ts),
    }


def main():
    parser = argparse.ArgumentParser(description="Verifica estatísticas reais de stream SRT no MediaMTX.")
    parser.add_argument("-d", "--duration", type=int, default=10, help="Tempo de medição em segundos (padrão: 10)")
    parser.add_argument("-p", "--path", default="drone_1", help="Path do stream no MediaMTX (padrão: drone_1)")
    parser.add_argument("--rtsp", default="rtsp://localhost:8554", help="Base URL RTSP (padrão: rtsp://localhost:8554)")
    parser.add_argument("--api", default="http://localhost:9997", help="URL da API do MediaMTX (padrão: http://localhost:9997)")
    args = parser.parse_args()

    rtsp_url = f"{args.rtsp}/{args.path}"
    print(f"Medindo '{rtsp_url}' por {args.duration}s...")

    stream_stats = measure_stream(rtsp_url, args.duration)
    srt_stats = get_mediamtx_srt_stats(args.api, args.path)

    bitrate = f"{srt_stats['mbpsReceiveRate']:.2f} Mbps" if srt_stats else "N/A"
    srt_latency = f"{srt_stats['msReceiveTsbPdDelay']} ms" if srt_stats else "N/A"
    rtt = f"{srt_stats['msRTT']:.2f} ms" if srt_stats else "N/A"
    fps_str = f"{stream_stats['fps']:.2f} fps ({stream_stats['frames']} frames)" if stream_stats["fps"] else "N/A"
    kf_str = f"{stream_stats['kf_avg']:.2f} s ({stream_stats['kf_count']} keyframes)" if stream_stats["kf_avg"] else "N/A"

    print("\n" + "=" * 45)
    print(f"  ESTATÍSTICAS DO STREAM ({args.path})")
    print("=" * 45)
    print(f"  1. Resolução        : {stream_stats['resolution']}")
    print(f"  2. Bitrate (SRT)    : {bitrate}")
    print(f"  3. FPS real         : {fps_str}")
    print(f"  4. Keyframe interval: {kf_str}")
    print(f"  5. Latência SRT     : {srt_latency} (RTT: {rtt})")
    print("=" * 45)


if __name__ == "__main__":
    main()