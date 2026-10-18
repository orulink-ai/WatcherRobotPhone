/** Temporarily route an explicit phone request while preserving ordinary PI chat. */
import { spawn } from "node:child_process";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { registerPhoneBenchmark } from "./phone-extension.ts";

const directory = dirname(fileURLToPath(import.meta.url));
const phoneTools = ["phone_observe", "phone_action"];
const bootstrapPython = () => process.env.PHONE_BENCH_PYTHON ?? join(homedir(), ".local/share/jev-phone-research/android-jev/.venv/bin/python");
type Intent = { kind: "phone" | "chat" | "unsupported"; task?: Record<string, unknown>; exitRequested?: boolean; message?: string };

function jsonProcess(script: string, args: string[], input = ""): Promise<any> {
  return new Promise((resolve, reject) => {
    const child = spawn(bootstrapPython(), [script, ...args], { stdio: ["pipe", "pipe", "pipe"] });
    let output = ""; let failed = false;
    const stop = () => { failed = true; child.kill("SIGKILL"); reject(new Error("Phone routing helper unavailable")); };
    const timer = setTimeout(stop, 5000);
    child.stderr.resume();
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (text: string) => { output += text; if (output.length > 65536) stop(); });
    child.on("error", stop);
    child.stdin.on("error", stop);
    child.on("close", (code) => {
      clearTimeout(timer);
      if (failed) return;
      if (code !== 0) return reject(new Error("Phone routing helper unavailable"));
      try { resolve(JSON.parse(output)); } catch { reject(new Error("Invalid phone routing helper result")); }
    });
    child.stdin.end(input);
  });
}

function ordinaryTools(tools: string[]): string[] {
  return tools.filter(name => !phoneTools.includes(name) && !name.startsWith("mcp__watcher_android__android_"));
}

export function registerNormalPhoneRouting(pi: ExtensionAPI, helpers: { jsonProcess?: typeof jsonProcess } = {}) {
  const runJson = helpers.jsonProcess ?? jsonProcess;
  let active = false;
  let pending = false;
  let shutdown = false;
  let generation = 0;
  let routeEnv: Record<string, string> = {};
  let cachedEnv: Record<string, string> | undefined;
  let saved: { model: NonNullable<ExtensionContext["model"]>; tools: string[]; thinking: ReturnType<ExtensionAPI["getThinkingLevel"]> } | undefined;
  registerPhoneBenchmark(pi, {
    terminal: true, terminalText: "手机任务已由控制器回读验证。", primaryLabel: "本地 StartLux",
    enabled: () => active, normalizeInput: false, providerEnv: () => routeEnv,
  });
  const report = (ctx: ExtensionContext, text: string) => {
    pi.appendEntry("phone-normal-route-error", { message: text });
    if (ctx.hasUI) ctx.ui.notify(text, "warning"); else console.error(text);
  };
  const restore = async (ctx: ExtensionContext) => {
    const previous = saved;
    if (!previous) return;
    try {
      if (!await pi.setModel(previous.model)) report(ctx, "手机任务已停止，但原聊天模型暂时无法恢复；请重新选择聊天模型。");
    } catch { report(ctx, "手机任务已停止，但原聊天模型恢复失败；请重新选择聊天模型。"); }
    finally {
      pi.setActiveTools(previous.tools);
      pi.setThinkingLevel(previous.thinking);
      saved = undefined; active = false; routeEnv = {};
    }
  };
  pi.on("session_start", () => pi.setActiveTools(ordinaryTools(pi.getActiveTools())));
  pi.on("tool_call", event => {
    if (!active && (phoneTools.includes(event.toolName) || event.toolName.startsWith("mcp__watcher_android__android_"))) {
      return { block: true, reason: "No current explicit phone request is active" };
    }
  });
  pi.on("input", async (event, ctx) => {
    if (active || pending) {
      if (/^(?:停止|取消|结束)(?:当前|本次|这个)?(?:手机任务|手机操作|任务|操作)?[。！!\s]*$/.test(event.text.trim())) {
        generation += 1;
        if (active) ctx.abort();
        report(ctx, "已请求停止当前手机任务。");
      } else report(ctx, "当前手机任务仍在处理，请等待完成后再提交请求。");
      return { action: "handled" };
    }
    if (!/手机|小红书|屏幕|界面|页面|微信|抖音|支付宝|相册|相机|计算器|设置/.test(event.text)) return { action: "continue" };
    pending = true;
    const thisGeneration = ++generation;
    const current = () => !shutdown && thisGeneration === generation;
    try {
    let intent: Intent;
    try { intent = await runJson(join(directory, "phone_intent.py"), [], JSON.stringify({ text: event.text })); }
    catch { report(ctx, "手机请求识别暂不可用，未操作手机。"); return { action: "handled" }; }
    if (!current()) return { action: "handled" };
    if (intent.kind === "chat") return { action: "continue" };
    if (intent.kind !== "phone" || !intent.task) { report(ctx, "这条手机请求暂不支持，未操作手机。请明确应用、搜索词、单个目标和操作。"); return { action: "handled" }; }
    if (!ctx.isIdle() || event.streamingBehavior) { report(ctx, "当前回答仍在进行，请等待完成后再提交手机请求。"); return { action: "handled" }; }
    try {
      if (!cachedEnv) {
        const profile = await runJson(process.env.PHONE_PROFILE_SCRIPT ?? join(directory, "phone_profile.py"), ["--launch-env"]);
        if (!current()) return { action: "handled" };
        if (!profile?.env || typeof profile.env !== "object") throw new Error("Invalid profile");
        for (const [key, value] of Object.entries(profile.env)) {
          if (typeof value !== "string" || !/^[A-Z_][A-Z0-9_]*$/.test(key) || /(?:API_KEY|TOKEN|AUTHORIZATION|HEADERS?)$/.test(key)) throw new Error("Invalid profile environment");
        }
        cachedEnv = profile.env;
      }
      const model = ctx.modelRegistry.find("phone-control", "auto");
      if (!current() || !ctx.isIdle()) return { action: "handled" };
      if (!model || !ctx.model) throw new Error("Model unavailable");
      saved = { model: ctx.model, tools: ordinaryTools(pi.getActiveTools()), thinking: pi.getThinkingLevel() };
      if (!await pi.setModel(model)) throw new Error("Phone provider unavailable");
      if (!current() || !ctx.isIdle()) {
        await restore(ctx);
        return { action: "handled" };
      }
      routeEnv = { ...cachedEnv };
      active = true;
      pi.setThinkingLevel("off");
      pi.setActiveTools(phoneTools);
      pi.appendEntry("phone-normal-route", { provider: "phone-control", model: "auto", primary: "local-startlux", fallback: "cloud-deepseek", taskKind: intent.task.kind, exitRequested: intent.exitRequested === true });
      return { action: "continue" };
    } catch {
      await restore(ctx);
      report(ctx, "本地手机决策入口暂不可用，未操作手机；请检查手机配置。");
      return { action: "handled" };
    }
    } finally { pending = false; }
  });
  // Shared finalization was registered first and persists the real task result.
  pi.on("agent_end", async (_event, ctx) => restore(ctx));
  pi.on("session_shutdown", async (_event, ctx) => {
    shutdown = true; generation += 1;
    await restore(ctx);
  });
}

export default function normalPhoneExtension(pi: ExtensionAPI) { registerNormalPhoneRouting(pi); }
