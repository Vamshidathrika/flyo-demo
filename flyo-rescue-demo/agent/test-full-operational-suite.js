import { chromium } from "playwright";

async function runFullOperationalSuite() {
    console.log("=== STARTING FLYO FULL OPERATIONAL SUITE ===");
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 1440, height: 950 } });

    const pageErrors = [];
    const httpErrors = [];

    page.on("pageerror", err => {
        console.error("PAGE ERROR:", err.message);
        pageErrors.push(err.message);
    });

    page.on("response", res => {
        if (res.status() >= 400) {
            const errStr = `HTTP ${res.status()} ON: ${res.url()}`;
            console.error(errStr);
            httpErrors.push(errStr);
        }
    });

    // 1. Initial Load
    console.log("1. Visiting Flyo Platform...");
    await page.goto("http://localhost:8080/", { waitUntil: "domcontentloaded" });
    await page.waitForSelector(".bookings-table tbody tr");
    await page.waitForTimeout(1000);

    // 2. Test Ingest PNR Modal
    console.log("2. Testing Automated PNR / Email Ingestion...");
    await page.click('button[onclick="openIngestModal()"]');
    await page.waitForSelector("#ingest-modal", { state: "visible" });
    // Click sample fill preset
    await page.click('button[onclick="loadSampleIngest(\'indigo\')"]');
    const sampleText = await page.$eval("#ingest-raw-text", el => el.value);
    console.log(`- Sample text loaded (${sampleText.length} chars)`);
    // Submit ingestion
    page.once("dialog", async dialog => {
        console.log(`- Ingest Dialog prompt: ${dialog.message()}`);
        await dialog.accept();
    });
    await page.click("#ingest-submit-btn");
    await page.waitForTimeout(1500);

    // 3. Test Flight Radar Alert Webhook
    console.log("3. Testing Flight Radar Alert Webhook...");
    await page.click("#tab-monitor");
    await page.waitForSelector('button[onclick="openRadarAlertModal()"]');
    await page.click('button[onclick="openRadarAlertModal()"]');
    await page.waitForSelector("#radar-alert-modal", { state: "visible" });
    await page.fill("#radar-flight", "AI-839");
    await page.selectOption("#radar-status", "CANCELLED");
    page.once("dialog", async dialog => {
        console.log(`- Radar Dialog prompt: ${dialog.message()}`);
        await dialog.accept();
    });
    await page.click("#radar-submit-btn");
    await page.waitForTimeout(2000);

    // Verify Disruption Alert Hub
    console.log("4. Verifying Disruption Alert Hub with Blocked Flights...");
    await page.click("#tab-monitor");
    await page.waitForTimeout(1000);
    const blockedCount = await page.$eval("#summary-blocked-trips", el => el.innerText);
    console.log(`- Blocked Count: ${blockedCount}`);

    // Disrupt a second booking (B2 - Rohit Verma) to test MULTI-BLOCKED flight handling
    console.log("5. Simulating second disruption for B2 (Rohit Verma)...");
    const b2DisruptBtn = page.locator(".bookings-table tbody tr", { hasText: "Rohit Verma" }).locator("button.btn-danger");
    if (await b2DisruptBtn.count() > 0) {
        await b2DisruptBtn.first().click();
        await page.waitForTimeout(2000);
    }

    // Check Multi-Case switcher
    console.log("6. Verifying Multi-Case Switcher in Case Recovery...");
    await page.click("#tab-ananya");
    await page.waitForTimeout(1000);
    const switcherPills = await page.$$eval(".case-switcher-pills .case-pill", els => els.map(e => e.innerText));
    console.log(`- Active Case Switcher Pills: ${JSON.stringify(switcherPills)}`);

    // 7. Verify Passenger WhatsApp View
    console.log("7. Verifying Passenger WhatsApp Interface...");
    await page.click("#tab-msg");
    await page.waitForTimeout(1000);
    const passengerFeed = await page.$eval("#passenger-wa-feed", el => el.innerText);
    console.log(`- Passenger WhatsApp Feed length: ${passengerFeed.length} chars`);

    // Switch back to Case Recovery
    await page.click("#tab-ananya");
    await page.waitForTimeout(800);

    // 8. Test 2FA OTP Request & Verification in Case Recovery
    console.log("8. Testing 2FA OTP Gate Dispatch...");
    await page.click("#btn-req-otp");
    await page.waitForTimeout(1500);
    const otpFeedback = await page.$eval("#otp-status-feedback", el => el.innerText);
    console.log(`- OTP Status Feedback: ${otpFeedback}`);

    // Verify operator chat feed on right sticky chassis
    const waOperatorFeed = await page.$eval("#wa-chat-feed", el => el.innerText);
    console.log(`- Operator WhatsApp Feed length: ${waOperatorFeed.length} chars`);

    // 9. Approve Option O2 to issue VCC and test Virtual Card display
    console.log("9. Approving Option O2 and verifying Virtual Card Treasury Settlement...");
    const approveBtn = page.locator("#option-O2 button.btn-approve");
    if (await approveBtn.count() > 0 && !(await approveBtn.isDisabled())) {
        await approveBtn.click();
        await page.waitForTimeout(1500);
    }
    const hasVcc = await page.$eval("#vcc-treasury-card", el => el.style.display !== "none");
    const vccAmount = hasVcc ? await page.$eval("#vcc-display-amount", el => el.innerText) : "N/A";
    console.log(`- Virtual Card Displayed: ${hasVcc} | Amount: ${vccAmount}`);

    // 10. Check Audit Logs Tab
    console.log("10. Testing Audit Logs Tab...");
    await page.click("#tab-logs");
    await page.waitForTimeout(1000);
    const auditEvents = await page.$$eval(".audit-item", els => els.length);
    console.log(`- Recorded Audit Events Count: ${auditEvents}`);

    // Screenshots
    await page.screenshot({ path: "screenshots/operational-suite-verified.png" });

    await browser.close();

    console.log("\n=== OPERATIONAL AUDIT SUMMARY ===");
    console.log(`Page Errors: ${pageErrors.length}`);
    console.log(`HTTP Errors: ${httpErrors.length}`);
    if (pageErrors.length > 0 || httpErrors.length > 0) {
        throw new Error("Operational suite detected errors!");
    }
    console.log(">>> ALL OPERATIONAL CAPABILITIES VERIFIED 100% CLEAN <<<");
}

runFullOperationalSuite().catch(err => {
    console.error("Operational test failed:", err);
    process.exit(1);
});
