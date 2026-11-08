import fs from "fs";
import path from "path";
import vm from "vm";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SRC_DIR = path.resolve(__dirname, "../backend/src/main/resources/static");

const appJs = fs.readFileSync(path.join(SRC_DIR, "app.js"), "utf8");
new vm.Script(appJs);

// Check for common bugs / anti-patterns
if (appJs.includes("debugger;")) {
    console.error("Lint error: 'debugger' statement found in production code.");
    process.exit(1);
}

if (appJs.includes("console.log") && appJs.match(/console\.log/g).length > 50) {
    console.warn("Warning: High volume of console.log statements detected.");
}

console.log("Lint check passed with 0 errors.");
