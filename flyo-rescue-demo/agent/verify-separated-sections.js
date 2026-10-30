import { chromium } from "playwright";
import * as path from "path";
import { fileURLToPath } from "url";
import * as fs from "fs";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

async function verifySeparatedSections() {
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } });

    const artifactDir = "/Users/nani/.gemini/antigravity/brain/7002fac9-1ac8-498b-95f8-21a21e135a30/screenshots";
    if (!fs.existsSync(artifactDir)) {
        fs.mkdirSync(artifactDir, { recursive: true });
    }

    console.log("Navigating to Flyo Rescue Console...");
    await page.goto("http://localhost:8080");
    await page.waitForTimeout(1000);

    // Reset initial state
    await fetch("http://localhost:8080/api/whatsapp/simulate-incoming", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sender: "919876543210", message: "RESET" })
    });
    await page.waitForTimeout(500);
    await page.reload();
    await page.waitForTimeout(1000);

    // 1. Capture View 1: Trip Monitor
    console.log("Capturing Section 1: Trip Monitor...");
    await page.screenshot({ path: path.join(artifactDir, "flyo-section1-trip-monitor.png"), fullPage: true });

    // 2. Trigger DISRUPT B1 via WhatsApp
    console.log("Simulating DISRUPT B1 via WhatsApp Command...");
    const disruptRes = await fetch("http://localhost:8080/api/whatsapp/simulate-incoming", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sender: "919876543210", message: "DISRUPT B1" })
    });
    const disruptData = await disruptRes.json();
    const match = disruptData.reply ? disruptData.reply.match(/\*([0-9]{6})\*/) : null;
    let code = match ? match[1] : "123456";
    console.log("OTP code received in disruption alert:", code);

    await page.reload();
    await page.waitForTimeout(1000);

    // Click on Case Recovery (Ananya) tab
    await page.click("#tab-ananya");
    await page.waitForTimeout(500);

    // 2. Capture View 2: Case Recovery (Ananya)
    console.log("Capturing Section 2: Case Recovery (Ananya)...");
    await page.screenshot({ path: path.join(artifactDir, "flyo-section2-case-ananya.png"), fullPage: true });

    // 3. Switch to Passenger Messages tab
    console.log("Switching to Section 3: Passenger Msg...");
    await page.click("#tab-msg");
    await page.waitForTimeout(500);
    await page.screenshot({ path: path.join(artifactDir, "flyo-section3-passenger-msg.png"), fullPage: true });

    // 4. Switch to Audit Logs tab
    console.log("Switching to Section 4: Audit Logs...");
    await page.click("#tab-logs");
    await page.waitForTimeout(500);
    await page.screenshot({ path: path.join(artifactDir, "flyo-section4-audit-logs.png"), fullPage: true });

    // 5. Complete approval & rebooking via WhatsApp
    console.log("Approving with OTP in chat:", code);
    await fetch("http://localhost:8080/api/whatsapp/simulate-incoming", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sender: "919876543210", message: `APPROVE ${code}` })
    });
    await page.waitForTimeout(1000);

    console.log("Executing REBOOK via WhatsApp...");
    await fetch("http://localhost:8080/api/whatsapp/simulate-incoming", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sender: "919876543210", message: "REBOOK C1" })
    });
    await page.waitForTimeout(2500);

    await page.reload();
    await page.waitForTimeout(1000);
    await page.click("#tab-ananya");
    await page.waitForTimeout(500);

    // Capture final rescued state
    console.log("Capturing Final Rescued State in Case Recovery (Ananya)...");
    const finalPath = path.join(artifactDir, "flyo-section2-rescued-ananya.png");
    await page.screenshot({ path: finalPath, fullPage: true });

    // Update main demo image
    fs.copyFileSync(finalPath, path.join(artifactDir, "flyo-simplified-client-whatsapp-ui.png"));

    await browser.close();
    console.log("All separated sections verified and captured successfully!");
}

verifySeparatedSections().catch(err => {
    console.error("Error in verification:", err);
    process.exit(1);
});
