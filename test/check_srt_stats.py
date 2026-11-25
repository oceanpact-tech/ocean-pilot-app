#!/usr/bin/env python3
import argparse
import json
import statistics
import subprocess
import time
import urllib.request


def get_mediamtx_srt_stats(api_url: str, path: str) -> dict | None:
    try:
        with urllib.request.urlopen(f"{api_url}/v3/srtconns/list", timeout=3) as resp:
            data = json.loads(resp.read().decode())
        items = data.get("items", [])
        for item in items:
            if item.get("path") == path and item.get("state") == "publish":
                return item
        return items[0] if items else None
    except Exception:
        return None


def get_num(d: dict, *keys: str) -> int:
    for k in keys:
        if k in d and d[k] is not None:
            return int(d[k])
    return 0


def measure_stream(rtsp_url: str, api_url: str, path: str, duration: int) -> dict:
    cmd = [
        "ffprobe",
        "-v", "error",
        "-rtsp_transport", "tcp",
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
    bitrates = []
    rtts = []
    first_srt = None
    last_srt = None
    resolution = "N/A"
    start_time = None
    last_api_poll = 0.0
    elapsed = 0.0

    try:
        for line in proc.stdout:
            fields = dict(
                item.split("=", 1) for item in line.strip().split("|") if "=" in item
            )
            ts_str = fields.get("best_effort_timestamp_time")
            if not ts_str or ts_str == "N/A":
                continue

            now = time.monotonic()
            if start_time is None:
                start_time = now

            if resolution == "N/A" and "width" in fields and "height" in fields:
                resolution = f"{fields['width']}x{fields['height']}"

            timestamps.append(float(ts_str))
            if fields.get("key_frame") == "1":
                keyframe_ts.append(float(ts_str))

            # Amostra a API do MediaMTX a cada 2s (e na primeira leitura)
            if first_srt is None or (now - last_api_poll >= 2.0):
                last_api_poll = now
                srt = get_mediamtx_srt_stats(api_url, path)
                if srt:
                    if first_srt is None:
                        first_srt = srt
                    last_srt = srt
                    bitrates.append(srt["mbpsReceiveRate"])
                    rtts.append(srt["msRTT"])

            elapsed = now - start_time
            if elapsed >= duration:
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

    pkt_recv = pkt_loss = pkt_retrans = pkt_drop = 0
    if first_srt and last_srt:
        pkt_recv = max(0, get_num(last_srt, "packetsReceived") - get_num(first_srt, "packetsReceived"))
        pkt_loss = max(0, get_num(last_srt, "packetsReceivedLoss", "packetsReceivedLost") - get_num(first_srt, "packetsReceivedLoss", "packetsReceivedLost"))
        pkt_retrans = max(0, get_num(last_srt, "packetsReceivedRetrans") - get_num(first_srt, "packetsReceivedRetrans"))
        pkt_drop = max(0, get_num(last_srt, "packetsReceivedDrop") - get_num(first_srt, "packetsReceivedDrop"))

    return {
        "resolution": resolution,
        "fps": fps,
        "frames": len(timestamps),
        "kf_avg": kf_avg,
        "kf_count": len(keyframe_ts),
        "bitrate_avg": statistics.mean(bitrates) if bitrates else None,
        "rtt_avg": statistics.mean(rtts) if rtts else None,
        "rtt_max": max(rtts) if rtts else None,
        "srt_latency": last_srt["msReceiveTsbPdDelay"] if last_srt else None,
        "remote_addr": last_srt.get("remoteAddr", "N/A") if last_srt else "N/A",
        "pkt_recv": pkt_recv,
        "pkt_loss": pkt_loss,
        "pkt_retrans": pkt_retrans,
        "pkt_drop": pkt_drop,
        "elapsed": elapsed,
        "dropped_early": elapsed < (duration - 1),
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

    s = measure_stream(rtsp_url, args.api, args.path, args.duration)

    if s["dropped_early"]:
        print(f"\n[AVISO] O stream desconectou após {s['elapsed']:.1f}s (antes dos {args.duration}s solicitados).")

    bitrate = f"{s['bitrate_avg']:.2f} Mbps (média)" if s["bitrate_avg"] is not None else "N/A"
    fps_str = f"{s['fps']:.2f} fps ({s['frames']} frames)" if s["fps"] else "N/A"
    kf_str = f"{s['kf_avg']:.2f} s ({s['kf_count']} keyframes)" if s["kf_avg"] else "N/A"
    srt_lat = f"{s['srt_latency']} ms" if s["srt_latency"] is not None else "N/A"

    if s["rtt_avg"] is not None and s["rtt_max"] is not None:
        rtt_str = f"{s['rtt_avg']:.2f} ms média | {s['rtt_max']:.2f} ms máx"
        rec_lat = max(20, int(round(s["rtt_max"] * 4)))
        ratio = (s["srt_latency"] / s["rtt_max"]) if (s["srt_latency"] and s["rtt_max"] > 0) else 0
        margin_str = f"{ratio:.1f}x do RTT máx (recomendado >= {rec_lat} ms)"
    else:
        rtt_str = "N/A"
        margin_str = "N/A"

    loss_pct = (s["pkt_loss"] / s["pkt_recv"] * 100) if s["pkt_recv"] > 0 else 0.0

    print("\n" + "=" * 54)
    print(f"  ESTATÍSTICAS DO STREAM ({args.path}) [{s['elapsed']:.1f}s]")
    print("=" * 54)
    print(f"  1. Resolução        : {s['resolution']}")
    print(f"  2. Bitrate (SRT)    : {bitrate}")
    print(f"  3. FPS real         : {fps_str}")
    print(f"  4. Keyframe interval: {kf_str}")
    print(f"  5. Latência SRT     : {srt_lat}")
    print("-" * 54)
    print(f"  6. QUALIDADE DO LINK (origem: {s['remote_addr']})")
    print(f"     • RTT (SRT)      : {rtt_str}")
    print(f"     • Margem SRT     : {margin_str}")
    print(f"     • Pacotes        : {s['pkt_recv']} recv | {s['pkt_retrans']} retrans | {s['pkt_loss']} loss ({loss_pct:.2f}%) | {s['pkt_drop']} drop")
    print("=" * 54)


if __name__ == "__main__":
    main()


# #!/usr/bin/env python3
# import argparse
# import json
# import statistics
# import subprocess
# import time
# import urllib.request

# # use: ./check_srt_stats.py -d time(s) -p drone_name

# def get_mediamtx_srt_stats(api_url: str, path: str) -> dict | None:
#     try:
#         with urllib.request.urlopen(f"{api_url}/v3/srtconns/list", timeout=3) as resp:
#             data = json.loads(resp.read().decode())
#         items = data.get("items", [])
#         # Prioriza a conexão que está publicando no path informado
#         for item in items:
#             if item.get("path") == path and item.get("state") == "publish":
#                 return item
#         return items[0] if items else None
#     except Exception as e:
#         print(f"[AVISO] Falha ao consultar API do MediaMTX: {e}")
#         return None


# def measure_stream(rtsp_url: str, duration: int) -> dict:
#     cmd = [
#         "ffprobe",
#         "-v", "error",
#         "-select_streams", "v:0",
#         "-show_entries", "frame=key_frame,best_effort_timestamp_time,width,height",
#         "-of", "compact=p=0",
#         rtsp_url,
#     ]

#     proc = subprocess.Popen(
#         cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
#     )

#     timestamps = []
#     keyframe_ts = []
#     resolution = "N/A"
#     start_time = None

#     try:
#         for line in proc.stdout:
#             fields = dict(
#                 item.split("=", 1) for item in line.strip().split("|") if "=" in item
#             )
#             ts_str = fields.get("best_effort_timestamp_time")
#             if not ts_str or ts_str == "N/A":
#                 continue

#             ts = float(ts_str)
#             if start_time is None:
#                 start_time = time.monotonic()

#             if resolution == "N/A" and "width" in fields and "height" in fields:
#                 resolution = f"{fields['width']}x{fields['height']}"

#             timestamps.append(ts)
#             if fields.get("key_frame") == "1":
#                 keyframe_ts.append(ts)

#             if time.monotonic() - start_time >= duration:
#                 break
#     finally:
#         proc.terminate()
#         try:
#             proc.wait(timeout=2)
#         except subprocess.TimeoutExpired:
#             proc.kill()

#     fps = None
#     if len(timestamps) >= 2 and (timestamps[-1] - timestamps[0]) > 0:
#         fps = (len(timestamps) - 1) / (timestamps[-1] - timestamps[0])

#     kf_deltas = [t2 - t1 for t1, t2 in zip(keyframe_ts, keyframe_ts[1:])]
#     kf_avg = statistics.mean(kf_deltas) if kf_deltas else None

#     return {
#         "resolution": resolution,
#         "fps": fps,
#         "frames": len(timestamps),
#         "kf_avg": kf_avg,
#         "kf_count": len(keyframe_ts),
#     }


# def main():
#     parser = argparse.ArgumentParser(description="Verifica estatísticas reais de stream SRT no MediaMTX.")
#     parser.add_argument("-d", "--duration", type=int, default=10, help="Tempo de medição em segundos (padrão: 10)")
#     parser.add_argument("-p", "--path", default="drone_1", help="Path do stream no MediaMTX (padrão: drone_1)")
#     parser.add_argument("--rtsp", default="rtsp://localhost:8554", help="Base URL RTSP (padrão: rtsp://localhost:8554)")
#     parser.add_argument("--api", default="http://localhost:9997", help="URL da API do MediaMTX (padrão: http://localhost:9997)")
#     args = parser.parse_args()

#     rtsp_url = f"{args.rtsp}/{args.path}"
#     print(f"Medindo '{rtsp_url}' por {args.duration}s...")

#     stream_stats = measure_stream(rtsp_url, args.duration)
#     srt_stats = get_mediamtx_srt_stats(args.api, args.path)

#     bitrate = f"{srt_stats['mbpsReceiveRate']:.2f} Mbps" if srt_stats else "N/A"
#     srt_latency = f"{srt_stats['msReceiveTsbPdDelay']} ms" if srt_stats else "N/A"
#     rtt = f"{srt_stats['msRTT']:.2f} ms" if srt_stats else "N/A"
#     fps_str = f"{stream_stats['fps']:.2f} fps ({stream_stats['frames']} frames)" if stream_stats["fps"] else "N/A"
#     kf_str = f"{stream_stats['kf_avg']:.2f} s ({stream_stats['kf_count']} keyframes)" if stream_stats["kf_avg"] else "N/A"

#     print("\n" + "=" * 45)
#     print(f"  ESTATÍSTICAS DO STREAM ({args.path})")
#     print("=" * 45)
#     print(f"  1. Resolução        : {stream_stats['resolution']}")
#     print(f"  2. Bitrate (SRT)    : {bitrate}")
#     print(f"  3. FPS real         : {fps_str}")
#     print(f"  4. Keyframe interval: {kf_str}")
#     print(f"  5. Latência SRT     : {srt_latency} (RTT: {rtt})")
#     print("=" * 45)


# if __name__ == "__main__":
#     main()
