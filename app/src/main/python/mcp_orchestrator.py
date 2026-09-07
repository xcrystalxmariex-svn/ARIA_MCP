"""mcp_orchestrator - execution bridge for user-defined Python custom tools.

Runs inside the Chaquopy-embedded Python interpreter on Android. Java calls
``run_tool(code, args_json)`` (see McpServerService.runPythonTool). Each custom
tool's script is executed with the LLM-supplied arguments available as the
dict ``input``; the script's ``result`` variable (or the value of the last
expression, when it assigns nothing) is returned as a JSON string to Java.

Scripts may also print to stdout for side-effect logging; stdout is captured
and appended to the returned result only when ``result`` is empty.

Convention documented for users (shown when a Python tool is added):
    import json
    data = input.get("data", {})      # LLM arguments (a dict)
    result = { "ok": True, "echo": data }
"""

import json
import sys
import traceback
import io


def run_tool(code, args_json):
    """Execute one user Python snippet and return its result as a JSON string.

    Args:
        code:      the Python source the user pasted in the Add Custom Tool UI
        args_json: JSON text of the arguments the LLM supplied (may be "")
    """
    stdout_capture = io.StringIO()
    old_stdout = sys.stdout
    try:
        # Parse the LLM arguments into a plain dict.
        try:
            input_data = json.loads(args_json) if args_json else {}
            if not isinstance(input_data, dict):
                input_data = {"value": input_data}
        except Exception:
            input_data = {}

        # Redirect stdout so user print() calls are captured rather than lost.
        sys.stdout = stdout_capture

        # Build a fresh namespace per call. The user's script may read the
        # global 'input' and should assign its answer to 'result'.
        namespace = {
            "input": input_data,
            "result": None,
            "json": json,
            "sys": sys,
        }

        compiled = compile(code, "<custom_tool>", "exec")
        exec(compiled, namespace)

        result = namespace.get("result")
        if result is None:
            printed = stdout_capture.getvalue().strip()
            result = printed if printed else {"ok": True, "note": "Script finished with no output."}

        # Normalise to a JSON-serialisable answer.
        try:
            return json.dumps(result, default=str)
        except Exception:
            return json.dumps({"ok": True, "raw": str(result)}, default=str)

    except Exception as exc:
        tb = traceback.format_exc()
        return json.dumps({
            "ok": False,
            "error": str(exc),
            "traceback": tb[-2000:],
        }, default=str)
    finally:
        sys.stdout = old_stdout


def ping():
    """Used by the Java side to verify the Chaquopy engine is alive."""
    return "pong"
