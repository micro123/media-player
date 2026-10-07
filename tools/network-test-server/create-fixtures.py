#!/usr/bin/env python3
"""Create only self-owned network fixtures, including a sparse 5 GiB-offset file."""
from pathlib import Path
import shutil

project = Path(__file__).resolve().parents[2]
fixtures = project / "build/network-fixtures"
fixtures.mkdir(parents=True, exist_ok=True)
video = project / "app/src/androidTest/assets/feature-test-video.mp4"
shutil.copyfile(video, fixtures / "episode-01.mp4")
shutil.copyfile(video, fixtures / "中文 #02.mp4")
music = project / "app/src/androidTest/assets/music-tags-test.mp3"
if music.exists():
    shutil.copyfile(music, fixtures / "music-tags-test.mp3")
(fixtures / "Season").mkdir(exist_ok=True)
shutil.copyfile(video, fixtures / "Season/episode-02.mp4")
(fixtures / "list.m3u").write_text("#EXTM3U\n#EXTINF:-1,Episode One\nepisode-01.mp4\nSeason/episode-02.mp4\n", encoding="utf8")
(fixtures / "read-pattern.bin").write_bytes(bytes(range(256)) * 8192)
with (fixtures / "large-offset.bin").open("wb") as file:
    file.seek(5 * 1024**3)
    file.write(b"large-offset-ok")
