"""Create two fictitious Windows backups using each project's own ledger APIs."""
import argparse
import base64
import hashlib
import importlib
import io
import json
import sqlite3
import subprocess
import sys
import tempfile
import zipfile
from contextlib import closing
from pathlib import Path


def build(source, output, variant):
    sys.path.insert(0, str(source))
    app = importlib.import_module("app")
    ledger = importlib.import_module("ledger")
    backups = importlib.import_module("backups")
    portfolio = importlib.import_module("portfolio")
    from PIL import Image, ImageDraw

    output.mkdir(parents=True, exist_ok=True)
    date = "2026-11-14"
    with tempfile.TemporaryDirectory(dir=output) as temp:
        database = Path(temp) / "ledger.db"
        app.initialize(database)
        receipt = Image.new("RGB", (280, 140), "white")
        ImageDraw.Draw(receipt).text((14, 18), "MY RECORD TEST RECEIPT\n2026-11-14\nTOTAL 200.35", fill="black")
        image = io.BytesIO()
        receipt.save(image, format="PNG")
        with app.connect(database) as db:
            sequence = 0

            def save(action, **fields):
                nonlocal sequence
                sequence += 1
                return ledger.action(db, dict(action=action, request_id=f"android-fixture-{sequence:04d}", **fields))["id"]

            home = save("book", name="虚构家庭账本")
            personal = save("book", name="虚构个人账本")
            save("current_book", book_id=home)
            card = save("account", name="虚构个人储蓄卡", owner="personal", opening=100_000,
                        opening_day="2026-01-01", account_type="bank")
            cash = save("account", name="虚构家庭现金", owner="family", opening=10_000,
                        opening_day="2026-01-01", account_type="cash")
            bill = save("bill", book_id=home, account_id=card, category_id=6, day=date,
                        amount=20_035, kind="expense", note="虚构票据", attachments=[{
                            "name": "receipt.png", "content": base64.b64encode(image.getvalue()).decode()}])
            save("bill", book_id=personal, account_id=card, category_id=1, day=date,
                 amount=30_000, kind="income", note="虚构收入")
            save("bill", book_id=home, account_id=card, to_account_id=cash, day=date,
                 amount=10_010, kind="transfer", note="虚构转账")
            save("budget", book_id=home, month="2026-11", amount=50_000)
            loan = save("position", kind="borrow", owner="family", name="虚构借入", principal=500_000,
                        opening_only=True, day="2026-01-01")
            save("loan_plan", position_id=loan, year=2026, annual_rate="3.5", months=[10_000] * 12)
            asset = save("position", kind="asset", owner="family", name="虚构房产", subtype="property",
                         value=80_000_000, day="2026-01-01")
            if variant == "current":
                db.execute("UPDATE positions SET has_loan=1 WHERE id=?", (asset,))
                db.executemany("INSERT INTO asset_loans(position_id,day,balance) VALUES(?,?,?)", [
                    (asset, "2026-10-01", 50_000_000), (asset, "2026-11-01", 85_000_000)])
            save("archive", id=personal, archived=True)
            state = ledger.snapshot(db)
            point = portfolio.point(db, "family", "2026-11-30")
            media = db.execute("SELECT image FROM bill_attachments WHERE bill_id=?", (bill,)).fetchone()[0]
            assert len(state["books"]) == 2 and len(state["bills"]) == 3
            assert {row["id"]: row["balance"] for row in state["accounts"]} == {card: 99_955, cash: 20_010}
            assert point["cash"] == 20_010 and point["assets"] == 80_020_010
            debt = 85_500_000 if variant == "current" else 500_000
            assert (point["debts"], point["net"]) == (debt, 80_020_010 - debt)
            assert sum(row["amount"] for row in state["installments"]) == 120_000
            expected = {"variant": variant, "cutoff": "2026-11-30", "books": 2, "bills": 3,
                        "accounts": {card: 99_955, cash: 20_010}, "family_assets": point["assets"],
                        "family_debt": debt, "family_cash": point["cash"], "family_net": point["net"],
                        "attachment_sha256": hashlib.sha256(media).hexdigest(), "attachment_bill": bill,
                        "month": {"income": 30_000, "expense": 20_035, "net": 9_965},
                        "schema": {table: [column[1] for column in db.execute(f"PRAGMA table_info({table})")]
                                   for table in ("positions", "asset_loans", "accounts", "bills")}}
        snapshot = Path(temp) / "snapshot.db"
        backups.snapshot(database, snapshot)
        backups.stamp(snapshot, "snapshot_time", "2026-11-30T12:00:00+08:00")
        assert backups.validate(snapshot)["bills"] == 3
        with closing(sqlite3.connect(snapshot)) as db:
            assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
            assert db.execute("PRAGMA foreign_key_check").fetchall() == []
        (output / f"windows-{variant}.db").write_bytes(snapshot.read_bytes())
        (output / f"windows-{variant}.json").write_text(json.dumps(expected, indent=2), encoding="utf-8")
        print(f"PASS Windows {variant}: 2 books, 3 bills, 2 balances and 1 attachment verified")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--variant", choices=["release", "current"])
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    output = args.output.resolve()
    if args.variant:
        build(source, output, args.variant)
        return
    with tempfile.TemporaryDirectory() as work:
        release = Path(work)
        with zipfile.ZipFile(source / "dist" / "My-Record-APP-1.1.0-source.zip") as archive:
            archive.extractall(release)
        for variant, tree in (("release", release), ("current", source)):
            subprocess.run([sys.executable, "-B", __file__, "--source", str(tree), "--output", str(output),
                            "--variant", variant], check=True)
    release = json.loads((output / "windows-release.json").read_text(encoding="utf-8"))
    current = json.loads((output / "windows-current.json").read_text(encoding="utf-8"))
    assert not release["schema"]["asset_loans"] and current["schema"]["asset_loans"]
    assert release["attachment_sha256"] == current["attachment_sha256"]


if __name__ == "__main__":
    main()
