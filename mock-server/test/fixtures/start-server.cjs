const { fork } = require('node:child_process');
const path = require('node:path');

function startServer() {
  const script = path.join(__dirname, '../../server.js');
  const child = fork(script, [], {
    env: { ...process.env, HOST: '127.0.0.1', PORT: '0' },
    windowsHide: true,
    stdio: ['ignore', 'ignore', 'inherit', 'ipc'],
  });
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error('Mock server did not report its port'));
    }, 8000);
    child.once('error', error => {
      clearTimeout(timer);
      reject(error);
    });
    child.once('exit', (code, signal) => {
      clearTimeout(timer);
      reject(new Error(`Mock server exited before reporting its port (${code ?? signal})`));
    });
    child.once('message', ({ port }) => {
      clearTimeout(timer);
      resolve({ origin: 'http://127.0.0.1:' + port, child });
    });
  });
}

module.exports = { startServer };
