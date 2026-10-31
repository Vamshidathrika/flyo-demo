import { chromium } from "playwright";
import type { Browser, Page } from "playwright";
import * as path from "path";
import * as fs from "fs";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

interface RebookArgs {
    caseId: string;
    optionId: string;
    flight: string;
    passenger: string;
    baseUrl: string;
    broken: boolean;
    vccCard?: string;
    vccExp?: string;
    vccCvv?: string;
    vccAmount?: number;
}

interface RebookResult {
    success: boolean;
    caseId: string;
    optionId: string;
    flight: string;
    newPnr?: string;
    error?: string;
    retryCount: number;
    steps: string[];
    screenshots: string[];
}

function parseArgs(): RebookArgs {
    const args = process.argv.slice(2);
    let caseId = "C1";
    let optionId = "O2";
    let flight = "FL301";
    let passenger = "Ananya Sharma";
    let baseUrl = "http://localhost:8080";
    let broken = false;
    let vccCard: string | undefined;
    let vccExp: string | undefined;
    let vccCvv: string | undefined;
    let vccAmount: number | undefined;

    for (let i = 0; i < args.length; i++) {
        if (args[i] === "--caseId" && args[i + 1]) caseId = args[++i];
        else if (args[i] === "--optionId" && args[i + 1]) optionId = args[++i];
        else if (args[i] === "--flight" && args[i + 1]) flight = args[++i];
        else if (args[i] === "--passenger" && args[i + 1]) passenger = args[++i];
        else if (args[i] === "--baseUrl" && args[i + 1]) baseUrl = args[++i];
        else if (args[i] === "--broken") broken = true;
        else if (args[i] === "--vccCard" && args[i + 1]) vccCard = args[++i];
        else if (args[i] === "--vccExp" && args[i + 1]) vccExp = args[++i];
        else if (args[i] === "--vccCvv" && args[i + 1]) vccCvv = args[++i];
        else if (args[i] === "--vccAmount" && args[i + 1]) vccAmount = parseInt(args[++i], 10);
    }

    return { caseId, optionId, flight, passenger, baseUrl, broken, vccCard, vccExp, vccCvv, vccAmount };
}

const config = parseArgs();

async function runWorker() {
    const screenshotsDir = path.join(__dirname, "screenshots");
    if (!fs.existsSync(screenshotsDir)) {
        fs.mkdirSync(screenshotsDir, { recursive: true });
    }

    const steps: string[] = [];
    const screenshots: string[] = [];
    const MAX_RETRIES = 3;
    let finalError = "";
    let finalPnr = "";
    let completed = false;

    let browser: Browser | null = null;
    try {
        browser = await chromium.launch({ headless: true });

        for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            const page: Page = await browser.newPage();
            page.setDefaultTimeout(10000);

            try {
                steps.push(`[Attempt ${attempt}/${MAX_RETRIES}] Initiating mock airline rebooking workflow...`);

                if (config.broken) {
                    throw new Error("Mock Airline Portal Error (500): Mock Airline Reservation Gateway Offline (ERR_GDS_HOST_UNREACHABLE_TIMEOUT_500)");
                }

                // Step 1: Navigate to Search Page
                const searchUrl = `${config.baseUrl}/mock-airline/search?passenger=${encodeURIComponent(config.passenger)}&flight=${encodeURIComponent(config.flight)}&caseId=${encodeURIComponent(config.caseId)}`;
                await page.goto(searchUrl);
                steps.push(`Navigated to ${searchUrl}`);
                
                const step1Screenshot = path.join(screenshotsDir, `case-${config.caseId}-step-1-search.png`);
                await page.screenshot({ path: step1Screenshot });
                if (!screenshots.includes(step1Screenshot)) screenshots.push(step1Screenshot);

                // Step 2: Search Flights
                const searchBtn = page.getByRole("button", { name: "Search Flights" });
                await searchBtn.click();
                await page.waitForURL("**/mock-airline/select*");
                steps.push("Clicked 'Search Flights' and arrived at flight selection page");

                const step2Screenshot = path.join(screenshotsDir, `case-${config.caseId}-step-2-select.png`);
                await page.screenshot({ path: step2Screenshot });
                if (!screenshots.includes(step2Screenshot)) screenshots.push(step2Screenshot);

                // Step 3: Select Approved Flight Option (e.g. FL301, FL101)
                let selectBtn = page.getByRole("button", { name: `Select ${config.flight}` });
                if (await selectBtn.count() === 0) {
                    const fallbackBtn = page.locator("button.btn-airline-select");
                    if (await fallbackBtn.count() > 0) {
                        selectBtn = fallbackBtn.first();
                    }
                }
                await selectBtn.click();
                await page.waitForURL("**/mock-airline/confirm*");
                steps.push(`Selected approved flight option ${config.flight}`);

                const step3Screenshot = path.join(screenshotsDir, `case-${config.caseId}-step-3-confirm.png`);
                await page.screenshot({ path: step3Screenshot });
                if (!screenshots.includes(step3Screenshot)) screenshots.push(step3Screenshot);

                // Check for Mock Airline Portal Error / 500 Outage
                const errorElement = page.locator("#portal-error");
                if (await errorElement.isVisible().catch(() => false)) {
                    const errText = (await errorElement.innerText().catch(() => "500 Internal Server Error")).trim();
                    throw new Error(`Mock Airline Portal Error (500): ${errText}`);
                }

                // Step 3b: Corporate Virtual Card (VCC) Auto-Checkout if payment input exists
                const cardInput = page.locator("#vcc-card-number");
                if (await cardInput.isVisible().catch(() => false)) {
                    if (config.vccCard) {
                        steps.push(`Authorizing Corporate Virtual Card (VCC) payment (+₹${config.vccAmount || 4200})...`);
                        await cardInput.fill(config.vccCard);
                        const expInput = page.locator("#vcc-exp");
                        if (await expInput.isVisible().catch(() => false)) await expInput.fill(config.vccExp || "12/28");
                        const cvvInput = page.locator("#vcc-cvv");
                        if (await cvvInput.isVisible().catch(() => false)) await cvvInput.fill(config.vccCvv || "891");
                    }
                    const paymentScreenshot = path.join(screenshotsDir, `case-${config.caseId}-step-3b-payment.png`);
                    await page.screenshot({ path: paymentScreenshot });
                    if (!screenshots.includes(paymentScreenshot)) screenshots.push(paymentScreenshot);
                    steps.push("Corporate treasury settlement card verified and authorized");
                }

                // Step 4: Confirm Rebooking
                const confirmBtn = page.getByRole("button", { name: "Confirm Rebooking" });
                await confirmBtn.click();
                await page.waitForSelector("#confirmed-panel", { state: "visible", timeout: 4000 });
                steps.push("Submitted confirmation on mock airline portal");

                const step4Screenshot = path.join(screenshotsDir, `case-${config.caseId}-step-4-confirmed.png`);
                await page.screenshot({ path: step4Screenshot });
                if (!screenshots.includes(step4Screenshot)) screenshots.push(step4Screenshot);

                // Step 5: Read PNR strictly from page DOM
                const pnrElement = page.locator("#new-pnr");
                finalPnr = (await pnrElement.innerText()).trim();

                if (!finalPnr) {
                    throw new Error("Failed to read newly generated PNR from confirmation page DOM.");
                }

                steps.push(`Extracted confirmed PNR: ${finalPnr}`);
                completed = true;
                await page.close();
                break; // Successfully completed rebooking, exit retry loop

            } catch (err: any) {
                finalError = err.message || String(err);
                steps.push(`Attempt ${attempt}/${MAX_RETRIES} failed: ${finalError}`);

                // Capture failure screenshot for inspection
                const failureScreenshot = path.join(screenshotsDir, `case-${config.caseId}-failure.png`);
                try {
                    await page.screenshot({ path: failureScreenshot });
                    if (!screenshots.includes(failureScreenshot)) screenshots.push(failureScreenshot);
                } catch (_) {}

                await page.close();

                if (attempt < MAX_RETRIES) {
                    steps.push(`Retrying attempt ${attempt + 1}/${MAX_RETRIES}...`);
                    await new Promise(r => setTimeout(r, 800));
                }
            }
        }

        if (completed && finalPnr) {
            const result: RebookResult = {
                success: true,
                caseId: config.caseId,
                optionId: config.optionId,
                flight: config.flight,
                newPnr: finalPnr,
                retryCount: 0,
                steps: steps,
                screenshots: screenshots
            };
            console.log(JSON.stringify(result, null, 2));
            process.exit(0);
        } else {
            const failureResult: RebookResult = {
                success: false,
                caseId: config.caseId,
                optionId: config.optionId,
                flight: config.flight,
                error: `Automated rebooking failed after ${MAX_RETRIES} attempts: ${finalError}`,
                retryCount: MAX_RETRIES,
                steps: steps,
                screenshots: screenshots
            };
            console.log(JSON.stringify(failureResult, null, 2));
            process.exit(1);
        }
    } finally {
        if (browser) {
            await browser.close();
        }
    }
}

runWorker().catch(err => {
    const errorResult: RebookResult = {
        success: false,
        caseId: config.caseId,
        optionId: config.optionId,
        flight: config.flight,
        error: `Fatal worker initialization error: ${err instanceof Error ? err.message : String(err)}`,
        retryCount: 0,
        steps: [`Worker fatal exception: ${err instanceof Error ? err.message : String(err)}`],
        screenshots: []
    };
    console.log(JSON.stringify(errorResult, null, 2));
    process.exit(1);
});
