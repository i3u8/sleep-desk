import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]


def workflow(name):
    # BaseLoader preserves GitHub's "on" key instead of YAML 1.1 boolean coercion.
    return yaml.load(
        (ROOT / ".github" / "workflows" / name).read_text(encoding="utf-8"),
        Loader=yaml.BaseLoader,
    )


class ReleaseWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.ci = workflow("android.yml")
        self.build = self.ci["jobs"]["build"]
        self.release = self.ci["jobs"]["release"]

    def action(self, job, prefix):
        return next(step for step in job["steps"] if step.get("uses", "").startswith(prefix))

    def test_main_push_and_pr_still_build(self):
        self.assertEqual(["main"], self.ci["on"]["push"]["branches"])
        self.assertEqual(["main"], self.ci["on"]["pull_request"]["branches"])

    def test_release_requires_successful_build_and_main_push(self):
        self.assertEqual("build", self.release["needs"])
        self.assertEqual(
            "github.event_name == 'push' && github.ref == 'refs/heads/main'",
            self.release["if"],
        )
        self.assertNotIn("always()", self.release["if"])

    def test_write_permission_is_scoped_to_release(self):
        self.assertEqual({"contents": "read"}, self.ci["permissions"])
        self.assertNotIn("permissions", self.build)
        self.assertEqual({"contents": "write"}, self.release["permissions"])

    def test_tested_artifact_is_reused_without_rebuild(self):
        upload = self.action(self.build, "actions/upload-artifact@")
        download = self.action(self.release, "actions/download-artifact@")
        self.assertEqual(upload["with"]["name"], download["with"]["name"])
        self.assertNotIn("run-id", download["with"])
        self.assertNotIn("repository", download["with"])
        build_steps = self.build["steps"]
        test_index = next(
            i for i, step in enumerate(build_steps)
            if "testDebugUnitTest assembleDebug" in step.get("run", "")
        )
        self.assertLess(test_index, build_steps.index(upload))
        self.assertFalse(any("gradlew" in step.get("run", "") for step in self.release["steps"]))

    def test_tag_is_per_run_and_reruns_are_idempotent(self):
        meta = next(step for step in self.release["steps"] if step.get("id") == "meta")
        self.assertIn("tag=main-${GITHUB_RUN_NUMBER}-${SHORT_SHA}", meta["run"])
        self.assertNotIn("GITHUB_RUN_ATTEMPT", meta["run"])
        publish = self.action(self.release, "softprops/action-gh-release@")
        self.assertEqual("${{ steps.meta.outputs.tag }}", publish["with"]["tag_name"])
        self.assertEqual("${{ github.sha }}", publish["with"]["target_commitish"])

    def test_missing_apk_is_fatal(self):
        meta = next(step for step in self.release["steps"] if step.get("id") == "meta")
        self.assertIn("set -euo pipefail", meta["run"])
        self.assertIn('test -s "release-apk/sleep-desk-debug-${SHORT_SHA}.apk"', meta["run"])
        publish = self.action(self.release, "softprops/action-gh-release@")
        self.assertEqual("true", publish["with"]["fail_on_unmatched_files"])
        self.assertEqual("release-apk/*.apk", publish["with"]["files"])

    def test_older_runs_do_not_force_latest(self):
        meta = next(step for step in self.release["steps"] if step.get("id") == "meta")
        self.assertIn('if [ "$MAIN_SHA" = "$GITHUB_SHA" ]', meta["run"])
        self.assertIn("make_latest=false", meta["run"])
        publish = self.action(self.release, "softprops/action-gh-release@")
        self.assertEqual(
            "${{ steps.meta.outputs.make_latest }}", publish["with"]["make_latest"]
        )
        self.assertEqual("main-auto-release", self.release["concurrency"]["group"])
        self.assertEqual("false", self.release["concurrency"]["cancel-in-progress"])

    def test_manual_version_release_remains_separate(self):
        manual = workflow("release.yml")
        self.assertEqual(["v*"], manual["on"]["push"]["tags"])
        self.assertIn("workflow_dispatch", manual["on"])
        self.assertNotIn("branches", manual["on"]["push"])


if __name__ == "__main__":
    unittest.main()
