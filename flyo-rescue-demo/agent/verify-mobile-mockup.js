import { chromium } from "playwright";
import * as path from "path";
import { fileURLToPath } from "url";
import * as fs from "fs";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

async function verifyMobileMockup() {
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 1440, height: 1080 } });

    const artifactDir = "/Users/nani/.gemini/antigravity/brain/7002fac9-1ac8-498b-95f8-21a21e135a30/screenshots";
    if (!fs.existsSync(artifactDir)) {
        fs.mkdirSync(artifactDir, { recursive: true });
    }

    console.log("1. Navigating to Flyo Console...");
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

    // 2. Trigger DISRUPT B1 via WhatsApp
    console.log("2. Simulating DISRUPT B1 via WhatsApp Command...");
    await fetch("http://localhost:8080/api/whatsapp/simulate-incoming", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sender: "919876543210", message: "DISRUPT B1" })
    });

    // 3. Operator selects Option O2
    console.log("3. Selecting Option O2 via WhatsApp...");
    const otpRes = await fetch("http://localhost:8080/api/whatsapp/simulate-incoming", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sender: "919876543210", message: "SELECT O2" })
    });
    const otpData = await otpRes.json();
    const match = otpData.reply ? otpData.reply.match(/\*([0-9]{6})\*/) : null;
    let code = match ? match[1] : "123456";
    console.log("Generated 2FA OTP Code:", code);

    await page.reload();
    await page.waitForTimeout(1000);

    // Switch to Passenger Msg tab
    console.log("4. Switching to Section 3: Passenger Msg (Ananya Phone Mockup)...");
    await page.click("#tab-msg");
    await page.waitForTimeout(800);

    // Simulate Ananya typing a question in the passenger phone
    console.log("5. Simulating passenger typing question in passenger smartphone...");
    const passengerInput = page.locator("#passenger-chat-input");
    await passengerInput.fill("Will my luggage be transferred automatically in Mumbai?");
    await page.click("#passenger-send-btn");
    await page.waitForTimeout(1200);

    // Capture interactive dialogue in passenger phone
    await page.screenshot({ path: path.join(artifactDir, "flyo-mobile-passenger-interactive.png"), fullPage: false });

    // 6. In operator WhatsApp phone on right, approve with OTP
    console.log("6. Authorizing with OTP in Operator Phone on right...");
    const waInput = page.locator("#wa-chat-input");
    await waInput.fill(code);
    await page.click("#wa-send-btn");
    await page.waitForTimeout(3000);

    // Switch to Case Recovery (Ananya) tab
    console.log("7. Switching to Case Recovery (Ananya) to view Rescued State...");
    await page.click("#tab-ananya");
    await page.waitForTimeout(1000);

    await page.screenshot({ path: path.join(artifactDir, "flyo-mobile-rescued-overview.png"), fullPage: true });

    // Switch back to Passenger Msg to view final confirmed ticket inside Ananya phone
    console.log("8. Viewing final confirmed ticket inside Ananya's phone...");
    await page.click("#tab-msg");
    await page.waitForTimeout(1000);

    await page.screenshot({ path: path.join(artifactDir, "flyo-mobile-passenger-final.png"), fullPage: false });

    // Focus screenshot on the right operator phone mockup
    const operatorPhone = page.locator(".platform-sidebar .mobile-phone-mockup");
    if (await operatorPhone.count() > 0) {
        await operatorPhone.screenshot({ path: path.join(artifactDir, "flyo-mobile-operator-phone-close.png") });
    }

    // Focus screenshot on the passenger phone mockup
    const passengerPhone = page.locator(".passenger-phone-mockup");
    if (await passengerPhone.count() > 0) {
        await passengerPhone.screenshot({ path: path.join(artifactDir, "flyo-mobile-passenger-phone-close.png") });
    }

    console.log("All mobile phone mockup screenshots captured successfully!");
    await browser.close();
}

verifyMobileMockup().catch(err => {
    console.error("Verification failed:", err);
    process.exit(1);
});
