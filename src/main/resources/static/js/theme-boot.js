/* Fondo elegido en Ajustes: se aplica antes de pintar para que no parpadee.
   (Antes era un <script> en línea; la CSP solo permite scripts de archivos propios.) */
try { if (localStorage.getItem('ecirace_bg') !== 'imagen') document.documentElement.classList.add('bg-pista'); }
catch (e) { document.documentElement.classList.add('bg-pista'); }
