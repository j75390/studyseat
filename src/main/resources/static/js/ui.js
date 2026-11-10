// Lucide 아이콘(<i data-lucide="...">)을 SVG로 바꾼다.
(function () {
  function init() { if (window.lucide) { window.lucide.createIcons(); } }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', init); } else { init(); }
})();
