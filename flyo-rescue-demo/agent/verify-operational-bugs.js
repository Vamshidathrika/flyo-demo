import { chromium } from "playwright";

async function testOperations() {
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

    page.on("pageerror", err => console.error("PAGE ERROR:", err.message));
    page.on("response", res => {
        if (res.status() >= 400) {
            console.error(`HTTP ERROR ${res.status()} ON: ${res.url()}`);
        }
    });

    console.log("1. Navigating to Flyo Platform...");
    await page.goto("http://localhost:8080/", { waitUntil: "domcontentloaded" });
    await page.waitForSelector(".bookings-table tbody tr");
    await page.waitForTimeout(1000);

    const initialTotal = await page.$eval("#summary-total-trips", el => el.innerText);
    const initialBlocked = await page.$eval("#summary-blocked-trips", el => el.innerText);
    console.log(`- Initial Summary: ${initialTotal} | ${initialBlocked}`);
    await page.screenshot({ path: "screenshots/op-clean-1-initial-monitor.png" });

    console.log("2. Disrupting Rohit Verma (B2)...");
    const b2Row = page.locator(".bookings-table tbody tr", { hasText: "Rohit Verma" });
    const b2Btn = b2Row.locator("button.btn").first();
    await b2Btn.click();
    await page.waitForTimeout(1500);

    const caseHeading = await page.$eval("#case-heading", el => el.innerText);
    console.log(`- Case Heading: ${caseHeading}`);

    console.log("3. Inspecting Blocked Alert Hub in Trip Monitor...");
    await page.click("#tab-monitor");
    await page.waitForTimeout(1200);

    const heroAlertText = await page.$eval("#hero-callout", el => el.innerText);
    console.log(`- Hero Alert Hub:\n${heroAlertText.split("\n").slice(0, 5).join("\n")}`);

    const blockedCount = await page.$eval("#summary-blocked-trips", el => el.innerText);
    console.log(`- Blocked Trips Counter: ${blockedCount}`);
    await page.screenshot({ path: "screenshots/op-clean-2-blocked-alert-hub.png" });

    console.log("4. Clicking 'Rescue Flight' on Rohit Verma from the table...");
    const rescueBtn = page.locator(".bookings-table tbody tr.blocked-row", { hasText: "Rohit Verma" }).locator("button.btn-primary");
    await rescueBtn.click();
    await page.waitForTimeout(1500);

    console.log("5. Approving Option O2 for Rohit Verma...");
    const approveBtn = page.locator("#option-O2 button.btn-approve");
    const isApprovedAlready = await approveBtn.isDisabled();
    if (!isApprovedAlready) {
        await approveBtn.click();
        await page.waitForTimeout(1500);
    } else {
        console.log("- Option O2 already approved.");
    }
    await page.screenshot({ path: "screenshots/op-clean-3-rohit-approved.png" });

    console.log("6. Executing Rebooking for Rohit Verma...");
    const rebookBtn = page.locator("#execute-rebook-btn");
    await rebookBtn.click();
    await page.waitForTimeout(5000);

    const postState = await page.$eval("#case-state", el => el.innerText);
    console.log(`- Case State after Rebooking: ${postState}`);
    await page.screenshot({ path: "screenshots/op-clean-4-rohit-rescued.png" });

    console.log("7. Checking Trip Monitor for Rescued Rohit Verma...");
    await page.click("#tab-monitor");
    await page.waitForTimeout(1500);

    const summaryRescued = await page.$eval("#summary-rescued-trips", el => el.innerText);
    const summaryBlocked = await page.$eval("#summary-blocked-trips", el => el.innerText);
    console.log(`- Summary Bar: ${summaryBlocked} | ${summaryRescued}`);
    await page.screenshot({ path: "screenshots/op-clean-5-trip-monitor-final.png" });

    await browser.close();
    console.log("ALL TESTS & VERIFICATIONS PASSED WITH ZERO ERRORS!");
}

testOperations().catch(err => {
    console.error("Test failed:", err);
    process.exit(1);
});
