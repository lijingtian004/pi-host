import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function (pi: ExtensionAPI) {
  pi.on("tool_call", async (event, ctx) => {
    if (event.toolName !== "bash") return undefined;
    const approved = await ctx.ui.confirm("Allow bash tool call?", String(event.input?.command ?? ""), { timeout: 15000 });
    if (!approved) {
      return { block: true, reason: "User denied the bash call from RPC UI" };
    }
    return undefined;
  });
}
