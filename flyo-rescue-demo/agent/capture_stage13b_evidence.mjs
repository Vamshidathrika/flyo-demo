import { chromium } from "playwright";
import * as path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const outputDir = path.join(__dirname, "screenshots", "stage13b");

async function capture() {
    const browser = await chromium.launch({ headless: true });
    const context = await browser.newContext({
        viewport: { width: 1440, height: 950 }
    });
    const page = await context.newPage();

    console.log("Navigating to http://localhost:8080...");
    await page.goto("http://localhost:8080", { waitUntil: "networkidle" });
    await page.waitForTimeout(1200);

    // 1. View 1: Trip Monitor
    console.log("Capturing View 1: Trip Monitor...");
    await page.screenshot({ path: path.join(outputDir, "view-1-trip-monitor.png"), fullPage: false });

    // Test filtering in Trip Monitor
    console.log("Testing flight filters...");
    const searchInput = page.locator("#flight-search-input");
    if (await searchInput.isVisible()) {
        await searchInput.fill("DEL");
        await page.waitForTimeout(500);
        await page.screenshot({ path: path.join(outputDir, "view-1-trip-monitor-filtered.png"), fullPage: false });
        await searchInput.fill("");
    }

    // 2. View 2: Incident Recovery
    console.log("Navigating to View 2: Incident Recovery...");
    const tabRecovery = page.locator("#tab-incident-recovery");
    if (await tabRecovery.isVisible()) {
        await tabRecovery.click();
    } else {
        await page.locator("#tab-ananya").click();
    }
    await page.waitForTimeout(1200);
    await page.screenshot({ path: path.join(outputDir, "view-2-incident-recovery.png"), fullPage: false });

    // 3. Contextual Drawer: Passenger Comms & 2FA
    console.log("Opening Contextual Drawer...");
    const drawerBtn = page.locator("#btn-toggle-operator-drawer");
    if (await drawerBtn.isVisible()) {
        await drawerBtn.click();
        await page.waitForTimeout(800);
        await page.screenshot({ path: path.join(outputDir, "contextual-drawer-comms.png"), fullPage: false });
        // Close drawer cleanly
        await page.evaluate(() => toggleOperatorDrawer());
        await page.waitForTimeout(500);
    }

    // 4. View 3: Operations Control
    console.log("Navigating to View 3: Operations Control...");
    await page.locator("#tab-ops").click();
    await page.waitForTimeout(1200);
    await page.screenshot({ path: path.join(outputDir, "view-3-operations-control.png"), fullPage: false });

    // 5. View 4: Audit Trail
    console.log("Navigating to View 4: Audit Trail...");
    await page.locator("#tab-logs").click();
    await page.waitForTimeout(1200);
    await page.screenshot({ path: path.join(outputDir, "view-4-audit-trail.png"), fullPage: false });

    console.log("All screenshots captured successfully in:", outputDir);
    await browser.close();
}

capture().catch(err => {
    console.error("Capture failed:", err);
    process.exit(1);
});
