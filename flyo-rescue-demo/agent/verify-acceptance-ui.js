import { chromium } from "playwright";
import * as path from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

async function captureAcceptanceScreenshots() {
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });

    // 1. Reset and run normal flow to DONE
    await fetch("http://localhost:8080/mock-airline/toggle-break?broken=false", { method: "POST" });
    await fetch("http://localhost:8080/api/cases/simulate?bookingId=B1&type=CANCELLED", { method: "POST" });
    await fetch("http://localhost:8080/api/cases/C1/options");
    await fetch("http://localhost:8080/api/cases/C1/approve", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ optionId: "O2", approvedBy: "Flyo Operator" })
    });
    await fetch("http://localhost:8080/api/cases/C1/rebook", { method: "POST" });

    // Open Dashboard
    await page.goto("http://localhost:8080");
    await page.waitForTimeout(1000);
    const rescuedPath = path.join(__dirname, "screenshots", "final-acceptance-rescued-dashboard.png");
    await page.screenshot({ path: rescuedPath, fullPage: true });
    console.log("Captured:", rescuedPath);

    // 2. Trigger Failure Mode
    await fetch("http://localhost:8080/mock-airline/toggle-break?broken=true", { method: "POST" });
    await fetch("http://localhost:8080/api/cases/simulate?bookingId=B1&type=CANCELLED", { method: "POST" });
    await fetch("http://localhost:8080/api/cases/C1/options");
    await fetch("http://localhost:8080/api/cases/C1/approve", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ optionId: "O2", approvedBy: "Flyo Operator" })
    });
    await fetch("http://localhost:8080/api/cases/C1/rebook", { method: "POST" });

    // Refresh Dashboard to display FAILED_ESCALATED
    await page.reload();
    await page.waitForTimeout(1000);
    const failurePath = path.join(__dirname, "screenshots", "final-acceptance-failure-escalated-dashboard.png");
    await page.screenshot({ path: failurePath, fullPage: true });
    console.log("Captured:", failurePath);

    // 3. Restore portal
    await fetch("http://localhost:8080/mock-airline/toggle-break?broken=false", { method: "POST" });

    await browser.close();
}

captureAcceptanceScreenshots().catch(console.error);
