"""MapleStory Launcher — Tk GUI die Cosmic server, MySQL en het admin dashboard supervised.

Zelfde patroon als neilchat/tradebot launchers. Start/stop via de bestaande
PowerShell scripts in scripts/.
"""

from __future__ import annotations

import ctypes
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import webbrowser
from pathlib import Path
from tkinter import BOTH, END, LEFT, RIGHT, Button, Canvas, Frame, Label, StringVar, Tk
from tkinter.scrolledtext import ScrolledText


FROZEN = getattr(sys, "frozen", False)


def get_root() -> Path:
    if FROZEN:
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parents[1]


ROOT = get_root()
POWERSHELL = r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"

COSMIC_DIR = ROOT / "server" if FROZEN else ROOT / "Cosmic"
ICON = ROOT / "maple.ico"
START_SCRIPT = ROOT / "scripts" / "start-server.ps1"
STOP_SCRIPT = ROOT / "scripts" / "stop-server.ps1"

LOGIN_PORT = 8484
DASHBOARD_PORT = 8787
MYSQL_PORT = 3307
CHANNEL_PORTS = [7575, 7576, 7577]

DASHBOARD_URL = f"http://127.0.0.1:{DASHBOARD_PORT}"
GAME_EXE = ROOT / "client" / "HeavenMS-localhost-WINDOW.exe" if FROZEN else Path(r"C:\Nexon\MapleStory\HeavenMS-localhost-WINDOW.exe")

LOGS_DIR = ROOT / "logs"
SERVER_OUT = LOGS_DIR / "server.out.log"
SERVER_ERR = LOGS_DIR / "server.err.log"

CREATE_NO_WINDOW = 0x08000000
CREATE_NEW_PROCESS_GROUP = 0x00000200

MAX_LOG_LINES = 2000

BG = "#1a1410"
SURFACE = "#221c16"
SURFACE_RAISED = "#2a2218"
TEXT = "#f2ece4"
TEXT_MUTED = "#a89880"
BORDER = "#3d3328"
PRIMARY = "#e8a835"
SUCCESS = "#5bc39d"
DANGER = "#e36055"

ANSI_RE = re.compile(r"\x1b\[([0-9;]*)m")
ANSI_COLORS = {
    "30": "#3d3328", "31": "#e36055", "32": "#5bc39d", "33": "#e8a835",
    "34": "#5b9ec3", "35": "#c35bc3", "36": "#5bc3c3", "37": "#f2ece4",
    "90": "#7a6e60", "91": "#ff8074", "92": "#7be0bd", "93": "#ffd76d",
    "94": "#7bb6e0", "95": "#e07be0", "96": "#7be0e0", "97": "#ffffff",
}

BAR_GREEN = "#5bc39d"
BAR_YELLOW = "#e8a835"
BAR_ORANGE = "#e67e22"
BAR_RED = "#e36055"


def _bar_color(pct: float) -> str:
    if pct < 50:
        return BAR_GREEN
    if pct < 75:
        return BAR_YELLOW
    if pct < 90:
        return BAR_ORANGE
    return BAR_RED


def parse_ansi(text: str) -> list[tuple[str, str | None]]:
    parts: list[tuple[str, str | None]] = []
    last_end = 0
    current: str | None = None
    for match in ANSI_RE.finditer(text):
        if match.start() > last_end:
            parts.append((text[last_end : match.start()], current))
        codes = match.group(1).split(";") if match.group(1) else ["0"]
        for code in codes:
            if code in ("0", "", "39"):
                current = None
            elif code in ANSI_COLORS:
                current = code
        last_end = match.end()
    if last_end < len(text):
        parts.append((text[last_end:], current))
    return parts


class MapleStoryLauncher:
    def __init__(self) -> None:
        self.root = Tk()
        self.root.title("MapleStory Launcher")
        self.root.geometry("900x580")
        self.root.minsize(820, 480)
        self.root.configure(bg=BG)
        if ICON.exists():
            self.root.iconbitmap(str(ICON))
        self._apply_dark_titlebar()

        self._active_tab = "launcher"
        self._tab_buffers: dict[str, list[str]] = {
            "launcher": [], "status": [], "server": [],
        }
        self._log_file_positions: dict[str, int] = {}
        self._latest_resources: dict | None = None
        self._latest_ports: dict[str, bool] = {}
        self._latest_channels_up: int = 0
        self._last_status_key: tuple = ()

        self._build_ui()
        self._init_log_positions()
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)
        self.refresh_status_loop()
        self._tail_logs_loop()

    def _build_ui(self) -> None:
        btn_style: dict = dict(
            bg=SURFACE_RAISED, fg=TEXT, activebackground=BORDER, activeforeground=TEXT,
            disabledforeground=TEXT_MUTED, highlightthickness=0, bd=1, relief="raised",
        )

        header = Frame(self.root, padx=14, pady=12, bg=BG)
        header.pack(fill="x")
        Label(header, text="MapleStory v83", font=("Segoe UI", 16, "bold"), bg=BG, fg=PRIMARY).pack(anchor="w")
        Label(
            header, text=f"Cosmic  ·  {DASHBOARD_URL}",
            font=("Segoe UI", 9), bg=BG, fg=TEXT_MUTED,
        ).pack(anchor="w", pady=(2, 0))

        status_row = Frame(header, bg=BG)
        status_row.pack(anchor="w", pady=(6, 0))

        self.mysql_dot = Label(status_row, text="●", bg=BG, fg=DANGER, font=("Segoe UI", 12))
        self.mysql_dot.pack(side=LEFT)
        Label(status_row, text=" MySQL   ", bg=BG, fg=TEXT_MUTED, font=("Segoe UI", 10)).pack(side=LEFT)

        self.cosmic_dot = Label(status_row, text="●", bg=BG, fg=DANGER, font=("Segoe UI", 12))
        self.cosmic_dot.pack(side=LEFT)
        self.cosmic_status_var = StringVar(value=" Cosmic —")
        Label(status_row, textvariable=self.cosmic_status_var, bg=BG, fg=TEXT_MUTED, font=("Segoe UI", 10)).pack(side=LEFT)
        Label(status_row, text="    ", bg=BG).pack(side=LEFT)

        self.dashboard_dot = Label(status_row, text="●", bg=BG, fg=DANGER, font=("Segoe UI", 12))
        self.dashboard_dot.pack(side=LEFT)
        Label(status_row, text=" Dashboard", bg=BG, fg=TEXT_MUTED, font=("Segoe UI", 10)).pack(side=LEFT)

        bars_row = Frame(header, bg=BG)
        bars_row.pack(anchor="w", fill="x", pady=(8, 0))
        self.ram_bar = ResourceBar(bars_row, label="RAM")
        self.ram_bar.pack(anchor="w")
        self.vram_bar = ResourceBar(bars_row, label="VRAM")
        self.vram_bar.pack(anchor="w", pady=(4, 0))

        buttons = Frame(self.root, padx=14, pady=10, bg=BG)
        buttons.pack(fill="x")
        self.start_button = Button(buttons, text="Start server", command=self.start_server, width=16, **btn_style)
        self.start_button.pack(side=LEFT, padx=(0, 8))
        self.stop_button = Button(buttons, text="Stop server", command=self.stop_server, width=16, **btn_style)
        self.stop_button.pack(side=LEFT, padx=(0, 8))
        self.refresh_button = Button(buttons, text="Refresh status", command=self.refresh_status, width=16, **btn_style)
        self.refresh_button.pack(side=LEFT, padx=(0, 8))
        Button(buttons, text="Launch game", command=self.launch_game, width=14, **btn_style).pack(side=RIGHT)
        Button(buttons, text="Open dashboard", command=lambda: webbrowser.open(DASHBOARD_URL), width=16, **btn_style).pack(side=RIGHT, padx=(0, 8))

        tab_bar = Frame(self.root, padx=14, bg=BG)
        tab_bar.pack(fill="x", pady=(4, 0))
        self._tab_buttons: dict[str, Button] = {}
        for tab_id, label in [
            ("launcher", "Launcher"),
            ("status", "Status"),
            ("server", "Server"),
        ]:
            btn = Button(
                tab_bar, text=label, command=lambda t=tab_id: self._switch_tab(t), width=12,
                fg=TEXT, activeforeground=TEXT, highlightthickness=0, bd=1, relief="flat",
            )
            btn.pack(side=LEFT, padx=(0, 2))
            self._tab_buttons[tab_id] = btn
        self._update_tab_buttons()

        self.log = ScrolledText(
            self.root, height=20, font=("Consolas", 9),
            bg=SURFACE, fg=TEXT, insertbackground=TEXT, selectbackground=PRIMARY,
            relief="flat", bd=0,
        )
        self.log.pack(fill=BOTH, expand=True, padx=14, pady=(4, 14))
        for code, color in ANSI_COLORS.items():
            self.log.tag_configure(f"ansi-{code}", foreground=color)
        self._log("Launcher klaar.")

    def _insert_log(self, text: str) -> None:
        for segment, code in parse_ansi(text):
            if code:
                self.log.insert(END, segment, f"ansi-{code}")
            else:
                self.log.insert(END, segment)

    def run(self) -> None:
        self.root.mainloop()

    def start_server(self) -> None:
        self._set_busy(True)
        self._reset_log_tracking("server", [SERVER_OUT, SERVER_ERR])
        run_in_thread(self._start_server_worker)

    def launch_game(self) -> None:
        if not GAME_EXE.exists():
            self._log(f"Game exe niet gevonden: {GAME_EXE}")
            return
        self._log("Game starten...")
        env = os.environ.copy()
        env["__COMPAT_LAYER"] = "WIN7RTM"
        subprocess.Popen(
            [str(GAME_EXE)], cwd=str(GAME_EXE.parent), env=env,
        )

    def stop_server(self) -> None:
        self._set_busy(True)
        self._log("Server stoppen (graceful via dashboard)...")
        run_in_thread(self._stop_server_worker)

    def refresh_status_loop(self) -> None:
        self.refresh_status()
        self.root.after(5000, self.refresh_status_loop)

    def refresh_status(self) -> None:
        run_in_thread(self._refresh_status_worker)

    def _start_server_worker(self) -> None:
        try:
            if http_ok(f"{DASHBOARD_URL}/api/status"):
                self._thread_log("Cosmic draait al.")
                self._refresh_status_worker()
                return

            if not START_SCRIPT.exists():
                self._thread_log(f"Start script niet gevonden: {START_SCRIPT}")
                return

            self._thread_log("Server starten via start-server.ps1...")
            cmd = [POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(START_SCRIPT)]
            if FROZEN:
                cmd += [
                    "-CosmicDir", str(ROOT / "server"),
                    "-JavaHome", str(ROOT / "runtime" / "java"),
                    "-MySqlBin", str(ROOT / "runtime" / "mysql" / "bin"),
                    "-MySqlDataDir", str(ROOT / "data" / "mysql-data"),
                ]
            start_process(cmd, ROOT, SERVER_OUT, SERVER_ERR)

            for _ in range(60):
                time.sleep(1)
                if http_ok(f"{DASHBOARD_URL}/api/status"):
                    self._thread_log("Server is online!")
                    break
            else:
                self._thread_log("Server nog niet online na 60s — check de Server tab voor logs.")

            self._refresh_status_worker()
        finally:
            self.root.after(0, lambda: self._set_busy(False))

    def _stop_server_worker(self) -> None:
        try:
            if not http_ok(f"{DASHBOARD_URL}/api/status"):
                self._thread_log("Server lijkt niet te draaien.")
                self._refresh_status_worker()
                return

            if not STOP_SCRIPT.exists():
                self._thread_log(f"Stop script niet gevonden: {STOP_SCRIPT}")
                return

            self._thread_log("Graceful shutdown via stop-server.ps1...")
            cmd = [POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(STOP_SCRIPT)]
            if FROZEN:
                cmd += [
                    "-CosmicDir", str(ROOT / "server"),
                    "-MySqlDataDir", str(ROOT / "data" / "mysql-data"),
                ]
            result = run_hidden(cmd)
            for line in result.stdout.splitlines():
                line = line.strip()
                if line:
                    self._thread_log(f"  {line}")
            for line in result.stderr.splitlines():
                line = line.strip()
                if line:
                    self._thread_log(f"  [err] {line}")

            time.sleep(1)
            if http_ok(f"{DASHBOARD_URL}/api/status"):
                self._thread_log("Server draait nog — mogelijk wacht de JVM op iets.")
            else:
                self._thread_log("Server gestopt.")
            self._refresh_status_worker()
        finally:
            self.root.after(0, lambda: self._set_busy(False))

    def _refresh_status_worker(self) -> None:
        try:
            mysql_ok = port_open("127.0.0.1", MYSQL_PORT)

            status = http_get_json(f"{DASHBOARD_URL}/api/status")
            dashboard_ok = status is not None
            cosmic_ok = dashboard_ok

            status_key = (mysql_ok, dashboard_ok)
            if status_key != self._last_status_key:
                self._last_status_key = status_key
                self._thread_log(f"Status: mysql={mysql_ok} dashboard={dashboard_ok}")

            channels_up = 0
            channel_status: dict[int, bool] = {}
            if status:
                for w in status.get("worlds", []):
                    channels_up += w.get("channels", 0)
                for p in CHANNEL_PORTS:
                    channel_status[p] = channels_up > 0

            resources = collect_resources()
            self._latest_resources = resources
            self._latest_ports = {
                "mysql": mysql_ok, "cosmic": cosmic_ok, "dashboard": dashboard_ok,
                **{f"ch_{p}": v for p, v in channel_status.items()},
            }
            self._latest_channels_up = channels_up

            self.root.after(0, lambda: self._apply_status(
                mysql_ok, cosmic_ok, dashboard_ok, channels_up, resources,
            ))
        except Exception as e:
            self._thread_log(f"Status check error: {e}")

    def _apply_status(
        self,
        mysql_ok: bool,
        cosmic_ok: bool,
        dashboard_ok: bool,
        channels_up: int,
        resources: dict,
    ) -> None:
        self.mysql_dot.configure(fg=SUCCESS if mysql_ok else DANGER)
        self.cosmic_dot.configure(fg=SUCCESS if cosmic_ok else DANGER)
        self.dashboard_dot.configure(fg=SUCCESS if dashboard_ok else DANGER)

        if cosmic_ok:
            ch_text = f" Cosmic — {channels_up} ch" if channels_up else " Cosmic (login only)"
            self.cosmic_status_var.set(ch_text)
        else:
            self.cosmic_status_var.set(" Cosmic —")

        ram = resources.get("ram") or {}
        if ram.get("total_bytes"):
            self.ram_bar.update_values(ram["used_bytes"], ram["total_bytes"], unit="GB", divisor=1024 ** 3)
        else:
            self.ram_bar.set_unavailable("n/a")

        gpus = resources.get("gpus") or []
        if gpus:
            g = gpus[0]
            self.vram_bar.update_values(g["vram_used_bytes"], g["vram_total_bytes"], unit="GB", divisor=1024 ** 3)
        else:
            self.vram_bar.set_unavailable("geen GPU")

        self._render_status_tab()

    def _render_status_tab(self) -> None:
        lines: list[str] = []
        res = self._latest_resources or {}
        ports = self._latest_ports

        lines.append(f"Dashboard: {DASHBOARD_URL}\n")
        lines.append(f"Cosmic:    {COSMIC_DIR}\n")
        lines.append(f"Login:     127.0.0.1:{LOGIN_PORT}\n")
        lines.append(f"Channels:  {', '.join(str(p) for p in CHANNEL_PORTS)}\n")

        lines.append("\n[Poorten]\n")
        lines.append(f"  MySQL ({MYSQL_PORT}):     {'online' if ports.get('mysql') else 'offline'}\n")
        lines.append(f"  Login ({LOGIN_PORT}):     {'online' if ports.get('cosmic') else 'offline'}\n")
        lines.append(f"  Dashboard ({DASHBOARD_PORT}): {'online' if ports.get('dashboard') else 'offline'}\n")
        for p in CHANNEL_PORTS:
            lines.append(f"  Channel ({p}):   {'online' if ports.get(f'ch_{p}') else 'offline'}\n")

        ram = res.get("ram") or {}
        gpus = res.get("gpus") or []
        lines.append("\n[System]\n")
        if ram.get("total_bytes"):
            used_gb = ram["used_bytes"] / 1024**3
            total_gb = ram["total_bytes"] / 1024**3
            pct = ram["used_bytes"] / ram["total_bytes"] * 100
            lines.append(f"  RAM: {used_gb:.1f} / {total_gb:.1f} GB ({pct:.0f}%)\n")
        else:
            lines.append("  RAM: (n/a)\n")
        if gpus:
            for g in gpus:
                used_gb = g["vram_used_bytes"] / 1024**3
                total_gb = g["vram_total_bytes"] / 1024**3
                lines.append(
                    f"  GPU {g.get('index', 0)} {g.get('name', '?')}: "
                    f"VRAM {used_gb:.1f} / {total_gb:.1f} GB · util {g.get('gpu_util_pct', '?')}%\n"
                )
        else:
            lines.append("  GPU: geen NVIDIA GPU gedetecteerd\n")

        self._tab_buffers["status"] = lines
        if self._active_tab == "status":
            yview_before = self.log.yview()
            self.log.delete("1.0", END)
            for line in lines:
                self._insert_log(line)
            self.log.yview_moveto(yview_before[0])

    def _switch_tab(self, tab_id: str) -> None:
        self._active_tab = tab_id
        self._update_tab_buttons()
        self.log.delete("1.0", END)
        for line in self._tab_buffers[tab_id]:
            self._insert_log(line)
        self.log.see(END)

    def _update_tab_buttons(self) -> None:
        for tab_id, btn in self._tab_buttons.items():
            if tab_id == self._active_tab:
                btn.configure(bg=SURFACE, activebackground=SURFACE)
            else:
                btn.configure(bg=SURFACE_RAISED, activebackground=BORDER)

    def _init_log_positions(self) -> None:
        for path in [SERVER_OUT, SERVER_ERR]:
            self._log_file_positions[str(path)] = path.stat().st_size if path.exists() else 0

    def _reset_log_tracking(self, tab_id: str, paths: list[Path]) -> None:
        self._tab_buffers[tab_id].clear()
        for path in paths:
            self._log_file_positions[str(path)] = path.stat().st_size if path.exists() else 0
        if self._active_tab == tab_id:
            self.log.delete("1.0", END)

    def _tail_logs_loop(self) -> None:
        self._tail_log_files("server", [SERVER_OUT, SERVER_ERR])
        self.root.after(1000, self._tail_logs_loop)

    def _tail_log_files(self, tab_id: str, paths: list[Path]) -> None:
        new_lines: list[str] = []
        for path in paths:
            key = str(path)
            if not path.exists():
                continue
            try:
                size = path.stat().st_size
                pos = self._log_file_positions.get(key, 0)
                if size <= pos:
                    continue
                with path.open("r", encoding="utf-8", errors="replace") as f:
                    f.seek(pos)
                    content = f.read()
                    self._log_file_positions[key] = f.tell()
                if content:
                    for line in content.splitlines(keepends=True):
                        if not line.endswith("\n"):
                            line += "\n"
                        new_lines.append(line)
            except OSError:
                pass

        if not new_lines:
            return

        buf = self._tab_buffers[tab_id]
        buf.extend(new_lines)
        if len(buf) > MAX_LOG_LINES:
            del buf[: len(buf) - MAX_LOG_LINES]

        if self._active_tab == tab_id:
            was_at_bottom = self.log.yview()[1] >= 0.999
            for line in new_lines:
                self._insert_log(line)
            if was_at_bottom:
                widget_lines = int(self.log.index("end-1c").split(".")[0])
                if widget_lines > MAX_LOG_LINES:
                    self.log.delete("1.0", f"{widget_lines - MAX_LOG_LINES}.0")
                self.log.see(END)

    def _apply_dark_titlebar(self) -> None:
        self.root.update_idletasks()
        try:
            hwnd = ctypes.windll.user32.GetParent(self.root.winfo_id())
            value = ctypes.c_int(1)
            ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(value), ctypes.sizeof(value))
        except Exception:
            pass

    def _on_close(self) -> None:
        self.root.destroy()

    def _log(self, message: str) -> None:
        timestamp = time.strftime("%H:%M:%S")
        line = f"[{timestamp}] {message}\n"
        self._tab_buffers["launcher"].append(line)
        if self._active_tab == "launcher":
            self._insert_log(line)
            self.log.see(END)

    def _thread_log(self, message: str) -> None:
        self.root.after(0, lambda: self._log(message))

    def _set_busy(self, busy: bool) -> None:
        state = "disabled" if busy else "normal"
        self.start_button.configure(state=state)
        self.stop_button.configure(state=state)
        self.refresh_button.configure(state=state)


class ResourceBar(Frame):
    BAR_WIDTH = 160
    BAR_HEIGHT = 10

    def __init__(self, parent: Frame, label: str) -> None:
        super().__init__(parent, bg=BG)
        self._label_text = StringVar(value=f"{label}")
        self._value_text = StringVar(value="—")
        Label(self, textvariable=self._label_text, bg=BG, fg=TEXT_MUTED, font=("Segoe UI", 9, "bold"), width=5, anchor="w").pack(side=LEFT)
        self._canvas = Canvas(
            self, width=self.BAR_WIDTH, height=self.BAR_HEIGHT,
            bg=SURFACE_RAISED, highlightthickness=1, highlightbackground=BORDER, bd=0,
        )
        self._canvas.pack(side=LEFT, padx=(4, 6))
        self._fill = self._canvas.create_rectangle(0, 0, 0, self.BAR_HEIGHT, fill=PRIMARY, outline="")
        Label(self, textvariable=self._value_text, bg=BG, fg=TEXT, font=("Consolas", 9)).pack(side=LEFT)

    def update_values(self, used_bytes: int, total_bytes: int, *, unit: str, divisor: int) -> None:
        if total_bytes <= 0:
            self.set_unavailable("n/a")
            return
        used = used_bytes / divisor
        total = total_bytes / divisor
        pct = max(0.0, min(100.0, used / total * 100.0))
        bar_w = max(1, int(self.BAR_WIDTH * pct / 100.0))
        self._canvas.coords(self._fill, 0, 0, bar_w, self.BAR_HEIGHT)
        self._canvas.itemconfigure(self._fill, fill=_bar_color(pct))
        self._value_text.set(f"{used:.1f} / {total:.1f} {unit} ({pct:.0f}%)")

    def set_unavailable(self, note: str = "—") -> None:
        self._canvas.coords(self._fill, 0, 0, 0, self.BAR_HEIGHT)
        self._value_text.set(note)


def collect_resources() -> dict:
    return {"ram": _collect_ram(), "gpus": _collect_gpus()}


def _collect_ram() -> dict:
    script = (
        "$os = Get-CimInstance Win32_OperatingSystem; "
        "Write-Output \"$($os.FreePhysicalMemory) $($os.TotalVisibleMemorySize)\""
    )
    result = run_hidden([POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script])
    parts = (result.stdout or "").strip().split()
    if len(parts) != 2:
        return {}
    try:
        free_kb = int(parts[0])
        total_kb = int(parts[1])
    except ValueError:
        return {}
    total_bytes = total_kb * 1024
    used_bytes = (total_kb - free_kb) * 1024
    return {"used_bytes": used_bytes, "total_bytes": total_bytes}


def _collect_gpus() -> list[dict]:
    try:
        result = run_hidden([
            "nvidia-smi",
            "--query-gpu=index,name,memory.used,memory.total,utilization.gpu",
            "--format=csv,noheader,nounits",
        ])
    except FileNotFoundError:
        return []
    if result.returncode != 0:
        return []
    gpus: list[dict] = []
    for line in result.stdout.splitlines():
        parts = [p.strip() for p in line.split(",")]
        if len(parts) < 5:
            continue
        try:
            gpus.append({
                "index": int(parts[0]),
                "name": parts[1],
                "vram_used_bytes": int(parts[2]) * 1024 * 1024,
                "vram_total_bytes": int(parts[3]) * 1024 * 1024,
                "gpu_util_pct": int(parts[4]),
            })
        except ValueError:
            continue
    return gpus


def start_process(
    command: list[str],
    cwd: Path,
    stdout_path: Path,
    stderr_path: Path,
) -> None:
    stdout = stdout_path.open("a", encoding="utf-8")
    stderr = stderr_path.open("a", encoding="utf-8")
    subprocess.Popen(
        command, cwd=cwd, stdout=stdout, stderr=stderr,
        creationflags=CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP,
    )


def http_ok(url: str) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=1.5) as response:
            return 200 <= response.status < 400
    except (OSError, urllib.error.URLError):
        return False


def http_get_json(url: str, timeout: float = 2.0) -> dict | None:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            if not (200 <= response.status < 300):
                return None
            data = json.loads(response.read().decode("utf-8"))
            return data if isinstance(data, dict) else None
    except (OSError, urllib.error.URLError, json.JSONDecodeError):
        return None


def port_open(host: str, port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(0.5)
        return sock.connect_ex((host, port)) == 0


def run_hidden(command: list[str]) -> subprocess.CompletedProcess[str]:
    startupinfo = subprocess.STARTUPINFO()
    startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    startupinfo.wShowWindow = 0
    return subprocess.run(
        command, capture_output=True, text=True,
        creationflags=CREATE_NO_WINDOW, startupinfo=startupinfo,
    )


def run_in_thread(target) -> None:
    import threading
    threading.Thread(target=target, daemon=True).start()


def main() -> int:
    if sys.platform != "win32":
        print("Deze launcher is bedoeld voor Windows.")
        return 1
    LOGS_DIR.mkdir(parents=True, exist_ok=True)
    ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("maplestory.launcher")
    app = MapleStoryLauncher()
    app.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
