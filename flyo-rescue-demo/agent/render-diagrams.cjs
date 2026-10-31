const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

async function renderDiagrams() {
    const mermaidJsPath = path.resolve(__dirname, 'node_modules/mermaid/dist/mermaid.min.js');
    if (!fs.existsSync(mermaidJsPath)) {
        throw new Error(`mermaid.min.js not found at ${mermaidJsPath}`);
    }
    const mermaidJs = fs.readFileSync(mermaidJsPath, 'utf8');

    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();

    // Set page content with mermaid embedded
    await page.setContent(`
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <style>
                body {
                    background-color: #18181B;
                    color: #F4F4F5;
                    font-family: 'Inter', system-ui, sans-serif;
                    margin: 0;
                    padding: 20px;
                }
            </style>
            <script>${mermaidJs}</script>
        </head>
        <body>
            <div id="container"></div>
        </body>
        </html>
    `);

    const workflowMmdPath = path.resolve(__dirname, '../../docs/diagrams/whatsapp-rescue-workflow.mmd');
    const sequenceMmdPath = path.resolve(__dirname, '../../docs/diagrams/whatsapp-rescue-sequence.mmd');

    const workflowCode = fs.readFileSync(workflowMmdPath, 'utf8');
    const sequenceCode = fs.readFileSync(sequenceMmdPath, 'utf8');

    console.log('Rendering whatsapp-rescue-workflow.mmd...');
    const workflowSvg = await page.evaluate(async (code) => {
        window.mermaid.initialize({
            startOnLoad: false,
            theme: 'dark',
            flowchart: {
                curve: 'basis',
                htmlLabels: true
            },
            themeVariables: {
                darkMode: true,
                background: '#18181B',
                primaryColor: '#27272A',
                primaryTextColor: '#F4F4F5',
                primaryBorderColor: '#3F3F46',
                lineColor: '#71717A',
                edgeLabelBackground: '#27272A',
                tertiaryColor: '#18181B',
                fontFamily: 'Inter, system-ui, sans-serif'
            }
        });
        const { svg } = await window.mermaid.render('workflowGraph', code);
        return svg;
    }, workflowCode);

    console.log('Rendering whatsapp-rescue-sequence.mmd...');
    const sequenceSvg = await page.evaluate(async (code) => {
        window.mermaid.initialize({
            startOnLoad: false,
            theme: 'dark',
            sequence: {
                showSequenceNumbers: true,
                actorFontSize: 14,
                messageFontSize: 13,
                boxMargin: 10
            },
            themeVariables: {
                darkMode: true,
                background: '#18181B',
                primaryColor: '#27272A',
                primaryTextColor: '#F4F4F5',
                primaryBorderColor: '#3F3F46',
                lineColor: '#71717A',
                actorBkg: '#27272A',
                actorBorder: '#3F3F46',
                actorTextColor: '#F4F4F5',
                signalColor: '#A1A1AA',
                signalTextColor: '#F4F4F5',
                labelBoxBkgColor: '#27272A',
                labelBoxBorderColor: '#3F3F46',
                labelTextColor: '#F4F4F5',
                loopTextColor: '#F4F4F5',
                noteBorderColor: '#3F3F46',
                noteBkgColor: '#27272A',
                noteTextColor: '#F4F4F5',
                fontFamily: 'Inter, system-ui, sans-serif'
            }
        });
        const { svg } = await window.mermaid.render('sequenceGraph', code);
        return svg;
    }, sequenceCode);

    await browser.close();

    // Target destinations
    const workflowSvgDest = path.resolve(__dirname, '../../docs/diagrams/whatsapp-rescue-workflow.svg');
    const workflowDocsSvgDest = path.resolve(__dirname, '../../docs/mermaid-diagram.svg');
    const workflowDownloadsSvgDest = '/Users/nani/Downloads/mermaid-diagram.svg';

    const sequenceSvgDest = path.resolve(__dirname, '../../docs/diagrams/whatsapp-rescue-sequence.svg');
    const sequenceDownloadsSvgDest = '/Users/nani/Downloads/sdw.svg';

    fs.writeFileSync(workflowSvgDest, workflowSvg);
    fs.writeFileSync(workflowDocsSvgDest, workflowSvg);
    if (fs.existsSync('/Users/nani/Downloads')) {
        fs.writeFileSync(workflowDownloadsSvgDest, workflowSvg);
    }

    fs.writeFileSync(sequenceSvgDest, sequenceSvg);
    if (fs.existsSync('/Users/nani/Downloads')) {
        fs.writeFileSync(sequenceDownloadsSvgDest, sequenceSvg);
    }

    console.log('Successfully generated and synchronized all SVGs:');
    console.log(`- ${workflowSvgDest}`);
    console.log(`- ${workflowDocsSvgDest}`);
    console.log(`- ${workflowDownloadsSvgDest}`);
    console.log(`- ${sequenceSvgDest}`);
    console.log(`- ${sequenceDownloadsSvgDest}`);
}

renderDiagrams().catch((err) => {
    console.error('Error rendering diagrams:', err);
    process.exit(1);
});
