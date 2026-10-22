// Тестовый игрок (offline-mode, только localhost) для проверки выдачи наград: node bot.js <port> <name> <seconds> [version]
// Нужен mineflayer (npm i mineflayer@4.39.0) в NODE_PATH. Печатает строки JSON: chat / items / end.
const mineflayer = require('mineflayer')
const [port, name, secs, version] = process.argv.slice(2)
const bot = mineflayer.createBot({ host: '127.0.0.1', port: +port, username: name, auth: 'offline', version: version || false })
const out = o => console.log(JSON.stringify(o))
const diamonds = () => bot.inventory.items().filter(i => i.name === 'diamond').reduce((a, i) => a + i.count, 0)
bot.on('messagestr', m => out({ chat: m }))
bot.once('spawn', () => {
  out({ spawned: true, version: bot.version })
  const t = setInterval(() => out({ diamonds: diamonds() }), 2000)
  setTimeout(() => { clearInterval(t); out({ end: true, diamonds: diamonds() }); bot.quit(); setTimeout(() => process.exit(0), 1000) }, (+secs) * 1000)
})
bot.on('kicked', r => { out({ kicked: String(r) }); process.exit(2) })
bot.on('error', e => { out({ error: String(e) }); process.exit(3) })
