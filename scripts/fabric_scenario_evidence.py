"""Validate local Fabric entrypoint execution; not full release acceptance."""

import re


def validate_scenarios(expected, trace, log, exit_code, backend):
    """Require every declared scenario, actual backend identity and clean GPU validation."""
    if backend not in ("opengl", "vulkan"):
        raise ValueError("Unknown requested backend")
    if type(exit_code) is not int or exit_code != 0:
        raise ValueError("Client did not exit successfully")
    if (not expected or any(not isinstance(name, str) or not name for name in expected)
            or len(set(expected)) != len(expected)):
        raise ValueError("Missing or duplicate expected scenarios")
    required = [state + "\t" + name for name in expected for state in ("started", "passed")]
    if trace.splitlines() != required:
        raise ValueError("Incomplete, reordered or diagnostic scenario trace")
    observed = re.findall(r"Readback backend=(\S+)", log)
    if not observed or any(value.lower() != backend for value in observed):
        raise ValueError("Missing or mismatched actual graphics backend")
    if re.search(r"VUID-|Validation Error|VK_ERROR_DEVICE_LOST|AssertionError|Game crashed!"
                 r"|GL_INVALID_(?:ENUM|VALUE|OPERATION|FRAMEBUFFER_OPERATION)|GL_OUT_OF_MEMORY", log):
        raise ValueError("Graphics validation or runtime failure in client log")
    return {"completedEntrypoints": len(expected), "backend": backend,
            "releaseAcceptance": False}
