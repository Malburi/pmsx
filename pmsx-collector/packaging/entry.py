"""PyInstaller 진입점 — pmsx-collector.exe run -c collector.yaml [--no-capture] | validate <model.json>"""
from pmsx_collector.cli import main

if __name__ == "__main__":
    raise SystemExit(main())
