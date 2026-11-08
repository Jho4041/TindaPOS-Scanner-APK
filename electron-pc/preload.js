const { contextBridge, ipcRenderer } = require('electron');
// lets the POS page share its customer-screen state with other devices on the Wi-Fi,
// and receive barcodes from the TindaPOS Scanner app (Android) over Wi-Fi
contextBridge.exposeInMainWorld('tpPC', {
  customerState: json => ipcRenderer.send('tp-customer-state', json),
  info: () => ipcRenderer.invoke('tp-server-info'),
  scannerStart: code => ipcRenderer.invoke('tp-scanner-start', code),
  scannerStop: () => ipcRenderer.invoke('tp-scanner-stop'),
  scannerStatus: () => ipcRenderer.invoke('tp-scanner-status'),
  onScan: cb => ipcRenderer.on('tp-scan', (e, v) => cb(v))
});
