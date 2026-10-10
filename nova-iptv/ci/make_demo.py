#!/usr/bin/env python3
"""Writes a demo M3U + XMLTV (programmes around 'now') for the CI emulator screenshots.

All channels point to Apple's public HLS sample stream (legal test content). Usage:
  make_demo.py <out_dir> <base_url>
"""
import datetime as dt
import pathlib
import sys

out = pathlib.Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
base = sys.argv[2].rstrip("/")
stream = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"
groups = {
    "News": ["World News 24", "Business Today", "Morning Report", "City Live", "Weather Now"],
    "Sports": ["Sport Arena", "Football Plus", "Motor Racing HD", "Tennis Court", "Extreme Sports"],
    "Movies": ["Cinema Classic", "Action Max", "Family Films", "Drama Channel", "Comedy Central Demo"],
    "Kids": ["Kids Planet", "Cartoon Time", "Learning Lab"],
    "Documentary": ["Nature World", "History Vault", "Science Hub", "Travel Explorer"],
}
titles = ["Evening News", "Live Match", "Feature Film", "Documentary: Oceans", "Morning Show",
          "Talk Tonight", "Cartoon Hour", "Weather Update", "Travel Diaries", "Science Today"]
m3u = [f'#EXTM3U url-tvg="{base}/epg.xml"']
xml = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv>']
now = dt.datetime.now(dt.timezone.utc).replace(minute=0, second=0, microsecond=0)
fmt = lambda t: t.strftime("%Y%m%d%H%M%S +0000")
n = 0
for group, names in groups.items():
    for name in names:
        n += 1
        cid = f"demo{n}"
        catchup = ' catchup="shift" catchup-days="3"' if n % 3 == 0 else ""
        m3u.append(f'#EXTINF:-1 tvg-id="{cid}" tvg-chno="{n}" group-title="{group}"{catchup},{name}')
        m3u.append(stream)
        xml.append(f'<channel id="{cid}"><display-name>{name}</display-name></channel>')
        t = now - dt.timedelta(hours=6)
        i = n
        while t < now + dt.timedelta(hours=18):
            dur = dt.timedelta(minutes=30 * (1 + (i % 4)))
            title = titles[i % len(titles)]
            xml.append(f'<programme start="{fmt(t)}" stop="{fmt(t + dur)}" channel="{cid}">'
                       f'<title>{title}</title><desc>{title} on {name}. Demo programme description.</desc>'
                       f'<category>{group}</category></programme>')
            t += dur; i += 1
xml.append('</tv>')
(out / "demo.m3u").write_text("\n".join(m3u) + "\n")
(out / "epg.xml").write_text("\n".join(xml) + "\n")
print(f"demo: {n} channels")
