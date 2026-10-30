import { chromium } from "playwright";

async function verify() {
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.goto("http://localhost:8080/");
    
    // Wait for bookings table to populate
    await page.waitForSelector(".bookings-table tbody tr.hero-row");
    
    const heroPassenger = await page.locator(".hero-row td:nth-child(2)").innerText();
    const heroPnr = await page.locator(".hero-row .pnr-tag").innerText();
    const totalRows = await page.locator(".bookings-table tbody tr").count();
    
    console.log(`UI Render Check:`);
    console.log(`- Hero Row Passenger: ${heroPassenger}`);
    console.log(`- Hero Row PNR: ${heroPnr}`);
    console.log(`- Total Rendered Rows: ${totalRows}`);
    
    // Save screenshot
    await page.screenshot({ path: "screenshots/chunk-1-dashboard.png", fullPage: true });
    console.log(`- Screenshot saved to: screenshots/chunk-1-dashboard.png`);
    
    await browser.close();
}

verify().catch(err => {
    console.error("UI Verification error:", err);
    process.exit(1);
});
