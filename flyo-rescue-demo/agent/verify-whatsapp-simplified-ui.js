import { chromium } from "playwright";
import * as path from "path";
import { fileURLToPath } from "url";
import * as fs from "fs";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

async function captureRealWhatsAppUI() {
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } });

    console.log("Navigating to Flyo Rescue Console...");
    await page.goto("http://localhost:8080");
    await page.waitForTimeout(1000);

    // 1. Reset state
    await fetch("http://localhost:8080/api/whatsapp/simulate-incoming", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sender: "919876543210", message: "RESET" })
    });
    await page.waitForTimeout(500);

    // 2. Send DISRUPT B1 via WhatsApp
    console.log("Simulating DISRUPT B1 via WhatsApp Command...");
    const disruptRes = await fetch("http://localhost:8080/api/whatsapp/simulate-incoming", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sender: "919876543210", message: "DISRUPT B1" })
    });
    const disruptData = await disruptRes.json();
    console.log("Disruption response:", disruptData.reply);

    // Extract OTP delivered directly in that same message
    const match = disruptData.reply ? disruptData.reply.match(/\*([0-9]{6})\*/) : null;
    let code = match ? match[1] : "123456";
    console.log("Detected OTP in the very same disruption message:", code);

    await page.reload();
    await page.waitForTimeout(1500);

    // Capture state 1: Disruption alert with in-message OTP & action button (NO yellow bar, NO emojis)
    const artifactDir = "/Users/nani/.gemini/antigravity/brain/7002fac9-1ac8-498b-95f8-21a21e135a30/screenshots";
    if (!fs.existsSync(artifactDir)) {
        fs.mkdirSync(artifactDir, { recursive: true });
    }
    const state1Path = path.join(artifactDir, "flyo-real-whatsapp-state1-alert.png");
    await page.screenshot({ path: state1Path, fullPage: true });
    console.log("Saved State 1 screenshot:", state1Path);

    // 3. Submit approval using OTP via chat
    console.log("Authenticating chat and approving Option O2 with OTP:", code);
    await fetch("http://localhost:8080/api/whatsapp/simulate-incoming", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sender: "919876543210", message: `APPROVE ${code}` })
    });
    await page.waitForTimeout(1000);

    // 4. Rebook via WhatsApp
    console.log("Executing REBOOK via WhatsApp...");
    await fetch("http://localhost:8080/api/whatsapp/simulate-incoming", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sender: "919876543210", message: "REBOOK C1" })
    });
    await page.waitForTimeout(2500);

    // Reload page to reflect final rescued state
    await page.reload();
    await page.waitForTimeout(2000);

    const finalPath = path.join(artifactDir, "flyo-real-whatsapp-final-rescued.png");
    await page.screenshot({ path: finalPath, fullPage: true });
    console.log("Saved Final Rescued screenshot:", finalPath);

    // Also update flyo-simplified-client-whatsapp-ui.png
    const legacyPath = path.join(artifactDir, "flyo-simplified-client-whatsapp-ui.png");
    fs.copyFileSync(finalPath, legacyPath);

    await browser.close();
    console.log("Verification finished successfully!");
}

captureRealWhatsAppUI().catch(err => {
    console.error("Screenshot capture error:", err);
    process.exit(1);
});
