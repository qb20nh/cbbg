import unittest

from fabric_scenario_evidence import graphics_identity, validate_scenarios


class FabricScenarioEvidenceTests(unittest.TestCase):
    def test_graphics_identity_preserves_observed_driver_and_extensions(self):
        log = ('Readback backend=OpenGL GPU=Fixture GPU driver=3.3 Fixture Driver\n'
               'Using graphics device extensions: GL_B, GL_A\n')
        result = graphics_identity(log + log, 'opengl')
        self.assertEqual(result['gpu'], 'Fixture GPU')
        self.assertEqual(result['driver'], '3.3 Fixture Driver')
        self.assertEqual(result['reportedExtensions'], ['GL_A', 'GL_B'])
        self.assertIsNone(result['contextProfile'])

    def test_graphics_identity_rejects_missing_conflicting_or_wrong_backend(self):
        log = 'Readback backend=Vulkan GPU=Fixture driver=1.4\n'
        for text, backend in [('', 'vulkan'), (log, 'opengl'),
                              (log + log.replace('1.4', '1.3'), 'vulkan')]:
            with self.subTest(text=text, backend=backend):
                with self.assertRaises(ValueError):
                    graphics_identity(text, backend)

    def setUp(self):
        self.expected = ["example.First", "example.Second"]
        self.trace = "started\texample.First\npassed\texample.First\nstarted\texample.Second\npassed\texample.Second\n"
        self.log = "Readback backend=Vulkan GPU=fixture\n"

    def verify(self, **changes):
        args = dict(expected=self.expected, trace=self.trace, log=self.log,
                    exit_code=0, backend="vulkan")
        args.update(changes)
        return validate_scenarios(**args)

    def test_complete_entrypoints_are_not_release_acceptance(self):
        result = self.verify()
        self.assertEqual(result["completedEntrypoints"], 2)
        self.assertFalse(result["releaseAcceptance"])

    def test_zero_exit_cannot_hide_vulkan_validation_error(self):
        with self.assertRaises(ValueError):
            self.verify(log=self.log + "VUID-vkCmdBlitImage-dstOffset-00248")

    def test_zero_exit_cannot_hide_logged_minecraft_crash(self):
        for error in ("Minecraft has crashed!", "Client gametests failed with an exception"):
            with self.subTest(error=error), self.assertRaises(ValueError):
                self.verify(log=self.log + error)

    def test_zero_exit_cannot_hide_opengl_errors(self):
        for error in ("GL_INVALID_ENUM", "GL_INVALID_VALUE", "GL_INVALID_OPERATION",
                      "GL_INVALID_FRAMEBUFFER_OPERATION", "GL_OUT_OF_MEMORY"):
            with self.subTest(error=error), self.assertRaises(ValueError):
                self.verify(backend="opengl", log="Readback backend=OpenGL\n" + error)

    def test_missing_reordered_duplicate_or_diagnostic_trace_fails(self):
        for trace in ("", self.trace.rsplit("passed", 1)[0],
                      self.trace + "passed\texample.Second\n",
                      "\n".join(reversed(self.trace.splitlines())),
                      self.trace + "diagnostic-options-reset-skipped\texample.Second\n"):
            with self.subTest(trace=trace), self.assertRaises(ValueError):
                self.verify(trace=trace)

    def test_backend_fallback_or_missing_identity_fails(self):
        for log in ("", "Readback backend=OpenGL GPU=fixture", self.log + "Readback backend=OpenGL"):
            with self.subTest(log=log), self.assertRaises(ValueError):
                self.verify(log=log)

    def test_invalid_contract_and_nonzero_exit_fail(self):
        for changes in ({"expected": []}, {"expected": ["same", "same"]},
                        {"expected": [None]}, {"exit_code": 255}, {"exit_code": False},
                        {"backend": "unknown"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.verify(**changes)


if __name__ == "__main__":
    unittest.main()
