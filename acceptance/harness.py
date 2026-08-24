#!/usr/bin/env python3
"""Small Android Gherkin harness used by this repository's acceptance suites."""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Match


class AcceptanceError(RuntimeError):
    pass


class PreflightError(AcceptanceError):
    pass


@dataclass
class Step:
    keyword: str
    text: str
    line: int
    argument: str | None = None


@dataclass
class Scenario:
    name: str
    line: int
    tags: list[str]
    steps: list[Step] = field(default_factory=list)


def parse_feature(path: Path) -> list[Scenario]:
    scenarios: list[Scenario] = []
    current: Scenario | None = None
    pending_tags: list[str] = []
    doc_step: Step | None = None
    doc_lines: list[str] = []

    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = raw.strip()
        if doc_step is not None:
            if stripped == '"""':
                doc_step.argument = "\n".join(doc_lines).strip()
                doc_step = None
                doc_lines = []
            else:
                doc_lines.append(raw.strip())
            continue
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("@"):
            pending_tags = stripped.split()
            continue
        if stripped.startswith("Scenario:"):
            current = Scenario(stripped.split(":", 1)[1].strip(), line_number, pending_tags)
            pending_tags = []
            scenarios.append(current)
            continue
        match = re.match(r"^(Given|When|Then|And|But)\s+(.+)$", stripped)
        if match:
            if current is None:
                raise AcceptanceError(f"{path}:{line_number}: step appears outside a Scenario")
            step = Step(match.group(1), match.group(2), line_number)
            current.steps.append(step)
            continue
        if stripped == '"""':
            if current is None or not current.steps:
                raise AcceptanceError(f"{path}:{line_number}: doc string has no preceding step")
            doc_step = current.steps[-1]
            doc_lines = []
            continue
        if stripped.startswith(("Feature:", "Rule:", "Background:")):
            continue
        if stripped.startswith(("Scenario Outline:", "Examples:")) or stripped.startswith("|"):
            raise AcceptanceError(f"{path}:{line_number}: outlines and data tables are not supported")
    if doc_step is not None:
        raise AcceptanceError(f"{path}: unterminated doc string")
    if not scenarios:
        raise AcceptanceError(f"{path}: no scenarios found")
    return scenarios


StepFunction = Callable[["RunContext", Match[str], str | None], None]


class Registry:
    def __init__(self) -> None:
        self.definitions: list[tuple[re.Pattern[str], StepFunction]] = []

    def step(self, expression: str) -> Callable[[StepFunction], StepFunction]:
        pattern = re.compile(f"^{expression}$")

        def register(function: StepFunction) -> StepFunction:
            self.definitions.append((pattern, function))
            return function

        return register

    def resolve(self, step: Step) -> tuple[StepFunction, Match[str]]:
        matches = [(function, match) for pattern, function in self.definitions if (match := pattern.match(step.text))]
        if not matches:
            raise AcceptanceError(f"line {step.line}: undefined step: {step.keyword} {step.text}")
        if len(matches) > 1:
            raise AcceptanceError(f"line {step.line}: ambiguous step: {step.keyword} {step.text}")
        return matches[0]


def _bounds(value: str) -> tuple[int, int, int, int]:
    numbers = [int(part) for part in re.findall(r"\d+", value)]
    if len(numbers) != 4:
        raise AcceptanceError(f"Invalid Android bounds: {value}")
    return numbers[0], numbers[1], numbers[2], numbers[3]


class AndroidDriver:
    def __init__(self, config: dict[str, object], report_dir: Path) -> None:
        self.adb_binary = os.environ.get("ADB", "adb")
        self.serial = os.environ.get("ADB_SERIAL", "")
        self.package = os.environ.get("ACCEPTANCE_APP_PACKAGE", str(config["package"]))
        self.activity = os.environ.get("ACCEPTANCE_APP_ACTIVITY", str(config.get("activity", "")))
        self.report_dir = report_dir
        self._last_tree: ET.Element | None = None

    def _base(self) -> list[str]:
        command = [self.adb_binary]
        if self.serial:
            command.extend(["-s", self.serial])
        return command

    def command(
        self,
        arguments: list[str],
        *,
        binary: bool = False,
        check: bool = True,
        timeout: int = 20,
    ) -> subprocess.CompletedProcess[bytes] | subprocess.CompletedProcess[str]:
        result = subprocess.run(
            self._base() + arguments,
            capture_output=True,
            text=not binary,
            timeout=timeout,
            check=False,
        )
        if check and result.returncode != 0:
            stderr = result.stderr.decode(errors="replace") if binary else result.stderr
            raise AcceptanceError(f"ADB command failed: {' '.join(arguments)}\n{stderr.strip()}")
        return result

    def shell(self, *arguments: str, check: bool = True, timeout: int = 20) -> str:
        result = self.command(["shell", *arguments], check=check, timeout=timeout)
        assert isinstance(result.stdout, str)
        return result.stdout.strip()

    def preflight(self) -> None:
        if not (Path(self.adb_binary).is_file() or shutil.which(self.adb_binary)):
            raise PreflightError(f"ADB executable is unavailable: {self.adb_binary}")
        result = subprocess.run(
            [self.adb_binary, "devices"], capture_output=True, text=True, timeout=10, check=False
        )
        if result.returncode != 0:
            raise PreflightError(f"ADB device discovery failed: {result.stderr.strip()}")
        devices = []
        for line in result.stdout.splitlines()[1:]:
            fields = line.split()
            if len(fields) >= 2 and fields[1] == "device":
                devices.append(fields[0])
        if self.serial:
            if self.serial not in devices:
                raise PreflightError(f"ADB_SERIAL {self.serial} is not an authorized connected device")
        elif len(devices) != 1:
            raise PreflightError(f"Real UAT requires exactly one authorized ADB device; found {len(devices)}")
        else:
            self.serial = devices[0]
        installed = self.shell("pm", "path", self.package, check=False)
        if not installed.startswith("package:"):
            raise PreflightError(
                f"Android package {self.package} is not installed; override ACCEPTANCE_APP_PACKAGE when testing another build type"
            )

    def clear_data(self) -> None:
        output = self.shell("pm", "clear", self.package)
        if "Success" not in output:
            raise AcceptanceError(f"Could not clear app data: {output}")
        time.sleep(0.4)

    def launch(self) -> None:
        self.shell("am", "force-stop", self.package)
        if self.activity:
            result = self.command(
                ["shell", "am", "start", "-W", "-n", self.activity], check=False, timeout=25
            )
            stdout = str(result.stdout)
            if result.returncode != 0 or "Error:" in stdout:
                raise AcceptanceError(f"Could not launch {self.activity}: {stdout.strip()}")
        else:
            self.shell("monkey", "-p", self.package, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(0.8)

    def relaunch(self) -> None:
        self.background()
        self.launch()

    def background(self) -> None:
        self.shell("input", "keyevent", "KEYCODE_HOME")
        time.sleep(0.5)

    def back(self) -> None:
        self.shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.4)

    def foreground(self) -> bool:
        output = self.shell("dumpsys", "activity", "activities")
        resumed = next((line for line in output.splitlines() if "mResumedActivity" in line), "")
        return self.package in resumed

    def dump_ui(self) -> ET.Element:
        remote = "/sdcard/acceptance-window.xml"
        self.shell("uiautomator", "dump", remote, timeout=15)
        result = self.command(["exec-out", "cat", remote], timeout=15)
        assert isinstance(result.stdout, str)
        try:
            tree = ET.fromstring(result.stdout)
        except ET.ParseError as error:
            raise AcceptanceError(f"Could not parse UIAutomator hierarchy: {error}") from error
        self._last_tree = tree
        return tree

    @staticmethod
    def _node_text(node: ET.Element) -> tuple[str, str]:
        return node.attrib.get("text", ""), node.attrib.get("content-desc", "")

    def nodes(self, text: str, *, contains: bool = False, refresh: bool = True) -> list[ET.Element]:
        tree = self.dump_ui() if refresh or self._last_tree is None else self._last_tree
        assert tree is not None
        found = []
        for node in tree.iter("node"):
            values = self._node_text(node)
            if any((text in value if contains else text == value) for value in values):
                found.append(node)
        return found

    def wait_nodes(
        self, text: str, *, contains: bool = False, present: bool = True, timeout: float = 8.0
    ) -> list[ET.Element]:
        deadline = time.monotonic() + timeout
        last: list[ET.Element] = []
        while time.monotonic() < deadline:
            last = self.nodes(text, contains=contains)
            if bool(last) == present:
                return last
            time.sleep(0.25)
        relation = "visible" if present else "absent"
        raise AcceptanceError(f"Expected Android text {text!r} to be {relation}")

    def find_with_scroll(self, text: str, *, contains: bool = False) -> ET.Element:
        for attempt in range(7):
            found = self.nodes(text, contains=contains)
            if found:
                return found[0]
            if attempt < 6:
                self.shell("input", "swipe", "540", "1700", "540", "500", "350")
                time.sleep(0.3)
        raise AcceptanceError(f"Android text is not visible after scrolling: {text}")

    def tap(self, text: str, *, contains: bool = False) -> None:
        node = self.find_with_scroll(text, contains=contains)
        left, top, right, bottom = _bounds(node.attrib.get("bounds", ""))
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(0.45)

    def tap_if_visible(self, text: str) -> bool:
        found = self.nodes(text)
        if not found:
            return False
        left, top, right, bottom = _bounds(found[0].attrib.get("bounds", ""))
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(0.35)
        return True

    def enabled(self, text: str) -> bool:
        node = self.find_with_scroll(text)
        return node.attrib.get("enabled", "false") == "true"

    def editable_nodes(self) -> list[ET.Element]:
        tree = self.dump_ui()
        return [
            node
            for node in tree.iter("node")
            if node.attrib.get("class", "").endswith("EditText") or node.attrib.get("editable") == "true"
        ]

    def replace_editable(self, one_based_index: int, value: str) -> None:
        fields = self.editable_nodes()
        if one_based_index < 1 or one_based_index > len(fields):
            raise AcceptanceError(f"Editable field {one_based_index} is unavailable; found {len(fields)}")
        node = fields[one_based_index - 1]
        left, top, right, bottom = _bounds(node.attrib.get("bounds", ""))
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        existing = node.attrib.get("text", "")
        self.shell("input", "keyevent", "KEYCODE_MOVE_END")
        remaining = max(len(existing), 160)
        while remaining > 0:
            batch = min(remaining, 40)
            self.shell("input", "keyevent", *(["KEYCODE_DEL"] * batch))
            remaining -= batch
        if value:
            encoded = value.replace("%", "\\%").replace(" ", "%s").replace("\n", "%n")
            self.shell("input", "text", encoded)
        time.sleep(0.35)

    def editable_value(self, one_based_index: int) -> str:
        fields = self.editable_nodes()
        if one_based_index < 1 or one_based_index > len(fields):
            raise AcceptanceError(f"Editable field {one_based_index} is unavailable")
        return fields[one_based_index - 1].attrib.get("text", "")

    def checkbox_state(self, label: str) -> bool:
        label_node = self.find_with_scroll(label, contains=True)
        label_bounds = _bounds(label_node.attrib.get("bounds", ""))
        tree = self._last_tree or self.dump_ui()
        candidates = [
            node
            for node in tree.iter("node")
            if node.attrib.get("checkable") == "true" or "CheckBox" in node.attrib.get("class", "")
        ]
        if not candidates:
            raise AcceptanceError(f"No checkable control is visible beside {label!r}")

        def distance(node: ET.Element) -> int:
            bounds = _bounds(node.attrib.get("bounds", ""))
            return abs(bounds[0] - label_bounds[0]) + abs(bounds[1] - label_bounds[1])

        return min(candidates, key=distance).attrib.get("checked", "false") == "true"

    def set_checkbox(self, label: str, expected: bool) -> None:
        if self.checkbox_state(label) != expected:
            self.tap(label, contains=True)
        if self.checkbox_state(label) != expected:
            raise AcceptanceError(f"Checkbox beside {label!r} did not become {expected}")

    def grant(self, permission: str) -> None:
        self.shell("pm", "grant", self.package, permission)

    def revoke(self, permission: str) -> None:
        self.shell("pm", "revoke", self.package, permission, check=False)

    def permission_granted(self, permission: str) -> bool:
        output = self.shell("dumpsys", "package", self.package)
        return re.search(rf"{re.escape(permission)}:\s+granted=true", output) is not None

    def declared_permission(self, permission: str) -> bool:
        output = self.shell("dumpsys", "package", self.package)
        requested = output.split("requested permissions:", 1)[-1].split("install permissions:", 1)[0]
        return permission in requested

    def private_file_count(self, pattern: str) -> int:
        command = f"find files -type f -name {shlex.quote(pattern)} 2>/dev/null"
        output = self.shell("run-as", self.package, "sh", "-c", command)
        return len([line for line in output.splitlines() if line.strip()])

    def screenshot(self, name: str) -> Path:
        safe = re.sub(r"[^A-Za-z0-9_.-]+", "-", name).strip("-") or "screen"
        destination = self.report_dir / "screenshots" / f"{safe}.png"
        destination.parent.mkdir(parents=True, exist_ok=True)
        result = self.command(["exec-out", "screencap", "-p"], binary=True, timeout=20)
        assert isinstance(result.stdout, bytes)
        destination.write_bytes(result.stdout)
        return destination

    def start_shared_text(self, text: str) -> None:
        self.shell(
            "am", "start", "-W", "-a", "android.intent.action.SEND", "-t", "text/plain",
            "--es", "android.intent.extra.TEXT", text, "-n", self.activity,
        )
        time.sleep(0.7)


class DemoHooks:
    def __init__(self, dry_run: bool) -> None:
        self.dry_run = dry_run
        self.recording_started = False

    @staticmethod
    def _run(name: str, extra_environment: dict[str, str] | None = None) -> None:
        command = os.environ.get(name, "").strip()
        if not command:
            return
        environment = os.environ.copy()
        environment.update(extra_environment or {})
        result = subprocess.run(shlex.split(command), env=environment, timeout=60, check=False)
        if result.returncode != 0:
            raise AcceptanceError(f"{name} failed with exit code {result.returncode}")

    def begin(self) -> None:
        if self.dry_run:
            return
        self._run("DEMO_RECORD_START_COMMAND")
        self.recording_started = True

    def finish(self) -> None:
        if self.dry_run:
            return
        if self.recording_started:
            self._run("DEMO_RECORD_STOP_COMMAND")
            self.recording_started = False

    def narrate(self, language: str, minimum_seconds: int, text: str) -> None:
        if self.dry_run:
            return
        started = time.monotonic()
        command = os.environ.get("DEMO_TTS_COMMAND", "").strip()
        if command:
            self._run(
                "DEMO_TTS_COMMAND",
                {
                    "DEMO_TTS_LANGUAGE": language,
                    "DEMO_TTS_TEXT": text,
                    "DEMO_TTS_MIN_SECONDS": str(minimum_seconds),
                },
            )
        else:
            print(f"[{language}] {text}", flush=True)
        remaining = minimum_seconds - (time.monotonic() - started)
        if remaining > 0:
            time.sleep(remaining)


@dataclass
class RunContext:
    driver: AndroidDriver
    hooks: DemoHooks
    dry_run: bool


def standard_registry() -> Registry:
    registry = Registry()

    @registry.step(r"the configured Android app is installed")
    def installed(context: RunContext, match: Match[str], argument: str | None) -> None:
        del context, match, argument

    @registry.step(r"all configured app data is cleared")
    def clear(context: RunContext, match: Match[str], argument: str | None) -> None:
        del match, argument
        context.driver.clear_data()

    @registry.step(r"I launch the configured Android app")
    def launch(context: RunContext, match: Match[str], argument: str | None) -> None:
        del match, argument
        context.driver.launch()

    @registry.step(r"I relaunch the configured Android app")
    def relaunch(context: RunContext, match: Match[str], argument: str | None) -> None:
        del match, argument
        context.driver.relaunch()

    @registry.step(r"I send the app to the background")
    def background(context: RunContext, match: Match[str], argument: str | None) -> None:
        del match, argument
        context.driver.background()

    @registry.step(r"I press Android back")
    def back(context: RunContext, match: Match[str], argument: str | None) -> None:
        del match, argument
        context.driver.back()

    @registry.step(r"I wait for (\d+) seconds")
    def wait(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        if not context.dry_run:
            time.sleep(int(match.group(1)))

    @registry.step(r'Android text "([^"]+)" is visible')
    def visible(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        context.driver.wait_nodes(match.group(1))

    @registry.step(r'Android text containing "([^"]+)" is visible')
    def contains_visible(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        context.driver.wait_nodes(match.group(1), contains=True)

    @registry.step(r'Android text "([^"]+)" is not visible')
    def not_visible(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        context.driver.wait_nodes(match.group(1), present=False, timeout=2.0)

    @registry.step(r'Android text "([^"]+)" is enabled')
    def enabled(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        if not context.driver.enabled(match.group(1)):
            raise AcceptanceError(f"Android text {match.group(1)!r} is disabled")

    @registry.step(r'Android text "([^"]+)" is disabled')
    def disabled(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        if context.driver.enabled(match.group(1)):
            raise AcceptanceError(f"Android text {match.group(1)!r} is enabled")

    @registry.step(r'I tap Android text "([^"]+)"')
    def tap(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        context.driver.tap(match.group(1))

    @registry.step(r'I tap Android text containing "([^"]+)"')
    def tap_contains(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        context.driver.tap(match.group(1), contains=True)

    @registry.step(r'I tap Android text "([^"]+)" if it is visible')
    def tap_optional(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        context.driver.tap_if_visible(match.group(1))

    @registry.step(r'I replace Android editable field (\d+) with:$')
    def replace_doc(context: RunContext, match: Match[str], argument: str | None) -> None:
        if argument is None:
            raise AcceptanceError("This step requires a doc string")
        context.driver.replace_editable(int(match.group(1)), argument)

    @registry.step(r'I replace Android editable field (\d+) with "([^"]*)"')
    def replace_value(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        context.driver.replace_editable(int(match.group(1)), match.group(2))

    @registry.step(r'Android editable field (\d+) has value "([^"]*)"')
    def field_value(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        actual = context.driver.editable_value(int(match.group(1)))
        if actual != match.group(2):
            raise AcceptanceError(f"Expected field value {match.group(2)!r}; got {actual!r}")

    @registry.step(r'I set the Android checkbox beside "([^"]+)" to (checked|unchecked)')
    def set_checkbox(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        context.driver.set_checkbox(match.group(1), match.group(2) == "checked")

    @registry.step(r'the Android checkbox beside "([^"]+)" is (checked|unchecked)')
    def checkbox(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        actual = context.driver.checkbox_state(match.group(1))
        expected = match.group(2) == "checked"
        if actual != expected:
            raise AcceptanceError(f"Checkbox beside {match.group(1)!r} is {actual}, expected {expected}")

    @registry.step(r'exactly (\d+) visible Android elements have text "([^"]+)"')
    def exact_count(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        actual = len(context.driver.nodes(match.group(2)))
        if actual != int(match.group(1)):
            raise AcceptanceError(f"Expected {match.group(1)} elements named {match.group(2)!r}; found {actual}")

    @registry.step(r'exactly (\d+) app-private files match "([^"]+)"')
    def private_count(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        actual = context.driver.private_file_count(match.group(2))
        if actual != int(match.group(1)):
            raise AcceptanceError(f"Expected {match.group(1)} private files matching {match.group(2)!r}; found {actual}")

    @registry.step(r'I grant Android permission "([^"]+)"')
    def grant(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        context.driver.grant(match.group(1))

    @registry.step(r'I revoke Android permission "([^"]+)"')
    def revoke(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        context.driver.revoke(match.group(1))

    @registry.step(r'Android permission "([^"]+)" is (granted|denied)')
    def permission(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        actual = context.driver.permission_granted(match.group(1))
        expected = match.group(2) == "granted"
        if actual != expected:
            raise AcceptanceError(f"Permission {match.group(1)} state is {actual}, expected {expected}")

    @registry.step(r'the app does not request Android permission "([^"]+)"')
    def not_declared(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        if context.driver.declared_permission(match.group(1)):
            raise AcceptanceError(f"Package unexpectedly requests {match.group(1)}")

    @registry.step(r'I allow the visible Android permission request')
    def allow_prompt(context: RunContext, match: Match[str], argument: str | None) -> None:
        del match, argument
        for label in ("While using the app", "Only this time", "Allow"):
            if context.driver.tap_if_visible(label):
                return
        raise AcceptanceError("No allow action is visible in the Android permission dialog")

    @registry.step(r'I deny the visible Android permission request')
    def deny_prompt(context: RunContext, match: Match[str], argument: str | None) -> None:
        del match, argument
        for label in ("Don't allow", "Deny"):
            if context.driver.tap_if_visible(label):
                return
        raise AcceptanceError("No deny action is visible in the Android permission dialog")

    @registry.step(r'the configured Android app remains foreground')
    def foreground(context: RunContext, match: Match[str], argument: str | None) -> None:
        del match, argument
        if not context.driver.foreground():
            raise AcceptanceError("The configured Android app is not foreground")

    @registry.step(r'the configured Android app is not foreground')
    def not_foreground(context: RunContext, match: Match[str], argument: str | None) -> None:
        del match, argument
        if context.driver.foreground():
            raise AcceptanceError("The configured Android app is still foreground")

    @registry.step(r'I share Android text "([^"]+)" directly to the configured app')
    def share_text(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        context.driver.start_shared_text(match.group(1))

    @registry.step(r'I run acceptance fixture action "([^"]+)"')
    def fixture(context: RunContext, match: Match[str], argument: str | None) -> None:
        del context, argument
        command = os.environ.get("ACCEPTANCE_FIXTURE_COMMAND", "").strip()
        if not command:
            raise AcceptanceError("ACCEPTANCE_FIXTURE_COMMAND is required for fixture-backed scenarios")
        environment = os.environ.copy()
        environment["ACCEPTANCE_FIXTURE_ACTION"] = match.group(1)
        result = subprocess.run(shlex.split(command), env=environment, timeout=60, check=False)
        if result.returncode != 0:
            raise AcceptanceError(f"Fixture action {match.group(1)!r} failed with {result.returncode}")

    @registry.step(r'I save an acceptance screenshot named "([^"]+)"')
    def screenshot(context: RunContext, match: Match[str], argument: str | None) -> None:
        del argument
        context.driver.screenshot(match.group(1))

    @registry.step(r'I begin a recorded demo')
    def begin_demo(context: RunContext, match: Match[str], argument: str | None) -> None:
        del match, argument
        context.hooks.begin()

    @registry.step(r'I finish the recorded demo')
    def finish_demo(context: RunContext, match: Match[str], argument: str | None) -> None:
        del match, argument
        context.hooks.finish()

    @registry.step(r'I narrate in "([^"]+)" for at least (\d+) seconds:$')
    def narrate(context: RunContext, match: Match[str], argument: str | None) -> None:
        if argument is None:
            raise AcceptanceError("Narration step requires a doc string")
        context.hooks.narrate(match.group(1), int(match.group(2)), argument)

    return registry


def _write_reports(
    report_dir: Path,
    feature_path: Path,
    suite: str,
    started_at: str,
    results: list[dict[str, object]],
) -> bool:
    report_dir.mkdir(parents=True, exist_ok=True)
    passed = all(bool(result["passed"]) for result in results)
    checklist = {
        "suite": suite,
        "passed": passed,
        "startedAt": started_at,
        "finishedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "feature": str(feature_path),
        "scenarios": results,
    }
    (report_dir / "checklist.json").write_text(json.dumps(checklist, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    lines = [f"# {suite} acceptance result", "", f"Overall: {'PASS' if passed else 'FAIL'}", ""]
    for result in results:
        lines.append(f"- [{'x' if result['passed'] else ' '}] {result['name']}")
    (report_dir / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    if suite == "uat":
        root = ET.Element("testExecutions", {"version": "1"})
        file_node = ET.SubElement(root, "file", {"path": str(feature_path)})
        for result in results:
            case = ET.SubElement(
                file_node,
                "testCase",
                {"name": str(result["name"]), "duration": str(int(float(result["durationSeconds"]) * 1000))},
            )
            if not result["passed"]:
                failure = ET.SubElement(case, "failure", {"message": str(result.get("error", "Acceptance failed"))})
                failure.text = str(result.get("error", "Acceptance failed"))
        ET.ElementTree(root).write(report_dir / "sonar-test-execution.xml", encoding="utf-8", xml_declaration=True)
    return passed


def run(root: Path, config: dict[str, object], arguments: argparse.Namespace) -> int:
    suite_files = {"uat": "uat.feature", "demo-en": "demo-en.feature", "demo-yue": "demo-yue.feature"}
    feature_path = root / "acceptance" / "features" / suite_files[arguments.suite]
    scenarios = parse_feature(feature_path)
    registry = standard_registry()

    binding_errors: list[str] = []
    for scenario in scenarios:
        for step in scenario.steps:
            try:
                registry.resolve(step)
            except AcceptanceError as error:
                binding_errors.append(f"{scenario.name}: {error}")
    if binding_errors:
        print("\n".join(binding_errors), file=sys.stderr)
        return 2

    report_dir = root / "build" / "reports" / "acceptance" / arguments.suite
    if arguments.dry_run:
        report_dir.mkdir(parents=True, exist_ok=True)
        validation = {
            "suite": arguments.suite,
            "feature": str(feature_path.relative_to(root)),
            "scenarios": len(scenarios),
            "steps": sum(len(scenario.steps) for scenario in scenarios),
            "allStepsBound": True,
            "productExecuted": False,
        }
        (report_dir / "binding-validation.json").write_text(
            json.dumps(validation, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
        print(
            f"DRY PASS {arguments.suite}: {validation['scenarios']} scenarios, "
            f"{validation['steps']} steps, all bindings unique; product not executed"
        )
        return 0

    driver = AndroidDriver(config, report_dir)
    try:
        driver.preflight()
    except PreflightError as error:
        print(f"REAL UAT SKIPPED BEFORE EXECUTION: {error}", file=sys.stderr)
        return 2

    if report_dir.exists():
        shutil.rmtree(report_dir)

    hooks = DemoHooks(False)
    context = RunContext(driver, hooks, False)
    started_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    results: list[dict[str, object]] = []
    try:
        for scenario_index, scenario in enumerate(scenarios, 1):
            scenario_started = time.monotonic()
            step_results: list[dict[str, object]] = []
            error_message = ""
            print(f"SCENARIO {scenario_index}/{len(scenarios)}: {scenario.name}")
            for step_index, step in enumerate(scenario.steps, 1):
                step_started = time.monotonic()
                try:
                    function, match = registry.resolve(step)
                    function(context, match, step.argument)
                    step_results.append(
                        {"text": step.text, "passed": True, "durationSeconds": time.monotonic() - step_started}
                    )
                except Exception as error:  # noqa: BLE001 - test evidence records any driver failure
                    error_message = f"line {step.line}: {type(error).__name__}: {error}"
                    step_results.append(
                        {
                            "text": step.text,
                            "passed": False,
                            "durationSeconds": time.monotonic() - step_started,
                            "error": error_message,
                        }
                    )
                    try:
                        driver.screenshot(f"failure-{scenario_index}-{step_index}")
                    except Exception:
                        pass
                    break
            scenario_passed = not error_message and len(step_results) == len(scenario.steps)
            try:
                driver.screenshot(f"scenario-{scenario_index}-{'pass' if scenario_passed else 'fail'}")
            except Exception:
                pass
            results.append(
                {
                    "name": scenario.name,
                    "passed": scenario_passed,
                    "durationSeconds": time.monotonic() - scenario_started,
                    "steps": step_results,
                    **({"error": error_message} if error_message else {}),
                }
            )
    finally:
        try:
            hooks.finish()
        except Exception as error:
            print(f"Could not stop demo recording: {error}", file=sys.stderr)
    return 0 if _write_reports(report_dir, feature_path.relative_to(root), arguments.suite, started_at, results) else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Run one Android Gherkin acceptance suite")
    parser.add_argument("suite", choices=("uat", "demo-en", "demo-yue"))
    parser.add_argument("--dry-run", action="store_true", help="validate parsing and bindings without ADB")
    arguments = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    config = json.loads((root / "acceptance" / "project.json").read_text(encoding="utf-8"))
    return run(root, config, arguments)


if __name__ == "__main__":
    raise SystemExit(main())
