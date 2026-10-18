import json
import pytest
from phone_profile import launch_environment


def test_profile_only_emits_references_not_credentials(tmp_path):
    config = tmp_path / "mcp.json"
    config.write_text(
        json.dumps(
            {
                "mcpServers": {
                    "phone": {
                        "url": "http://phone/mcp",
                        "headers": {"Authorization": "private-token"},
                    }
                }
            }
        )
    )
    profile = tmp_path / "profile.json"
    profile.write_text(
        json.dumps(
            {
                "python": "/runtime/python",
                "mcpConfig": str(config),
                "mcpServer": "phone",
                "recipe": str(tmp_path / "recipe.json"),
            }
        )
    )
    env = launch_environment(profile)
    assert env["ANDROID_MCP_URL"] == "http://phone/mcp"
    assert env["PHONE_BENCH_WORKER"].endswith("/phone_local_worker.py")
    assert env["PHONE_BENCH_STARTLUX_INSTANT_URL"] == "http://192.168.50.26:8094/v1/systemone"
    assert env["PHONE_BENCH_STARTLUX_ALLOW_LAN"] == "1"
    assert "private-token" not in json.dumps(env)
    assert not any("API_KEY" in k and not k.endswith("_FILE") for k in env)


def test_profile_does_not_override_chat_model_or_expose_header(tmp_path):
    config = tmp_path / "mcp.json"
    config.write_text(json.dumps({"mcpServers": {"phone": {"url": "http://phone/mcp"}}}))
    profile = tmp_path / "profile.json"
    profile.write_text(
        json.dumps({"python": "relative-python", "mcpConfig": str(config), "mcpServer": "phone"})
    )
    with pytest.raises(ValueError):
        launch_environment(profile)
