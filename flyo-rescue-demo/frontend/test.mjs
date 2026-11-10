import fs from "fs";
import path from "path";
import vm from "vm";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SRC_DIR = path.resolve(__dirname, "../backend/src/main/resources/static");

console.log("Running frontend asset validation and DOM contract tests...");

const appJs = fs.readFileSync(path.join(SRC_DIR, "app.js"), "utf8");
const indexHtml = fs.readFileSync(path.join(SRC_DIR, "index.html"), "utf8");

// Test 1: JS Compilation
new vm.Script(appJs);
console.log("✔ Test 1: JavaScript AST parse and syntax valid");

// Test 2: Required DOM selectors in index.html match app.js bindings
const requiredSelectors = [
    "bookings-tbody",
    "portal-status-label",
    "toggle-portal-btn",
    "refresh-btn",
    "refresh-telemetry-btn",
    "view-ananya"
];

for (const id of requiredSelectors) {
    if (!indexHtml.includes(`id="${id}"`)) {
        console.error(`✖ Test 2 FAILED: Expected DOM element #${id} missing from index.html`);
        process.exit(1);
    }
}
console.log("✔ Test 2: Core operator dashboard DOM selectors verified");

// Test 3: API endpoint alignment
const expectedEndpoints = [
    "/api/cases",
    "/api/bookings",
    "/api/alerts/flight-status",
    "/api/whatsapp/messages",
    "/mock-airline/toggle-break"
];

for (const ep of expectedEndpoints) {
    if (!appJs.includes(ep)) {
        console.error(`✖ Test 3 FAILED: Expected backend API path ${ep} not found in app.js`);
        process.exit(1);
    }
}
console.log("✔ Test 3: Backend API routes alignment verified");

console.log("All frontend tests passed cleanly (3/3).");
