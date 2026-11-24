'use strict';
/* =====================================================================
   CANDY MATCH SAGA — Thin Frontend Client for Java Localhost Engine
   All game rules, board physics, matching, synthesis, AI opponent,
   worth evaluation, persistence, and analytics execute in Java.
   ===================================================================== */
const R = 8, C = 8;
const $ = s => document.querySelector(s);
const esc = s => String(s).replace(/[&<>"']/g, m => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));
const sleep = ms => new Promise(r => setTimeout(r, ms));

let CFG = { sound: true, candies: {}, formulas: [], rules: [], icons: [], baseIds: [], levels: [] };
let S = null;            // Active session snapshot from Java server
let busy = false;
let sel = null;
let hint = null;
let pickedLevel = 1;
let selectedBook = null; // {type:'candy'|'formula'|'rule', id}
let bookTab = 'candies';
let runToken = 0;        // Cancels stale animations when restarting/leaving

/* ---------- HTTP API client ---------- */
async function api(url, opts = {}) {
  const fetchOpts = { method: opts.method || 'GET', headers: {} };
  if (opts.body !== undefined) {
    fetchOpts.headers['Content-Type'] = 'application/json';
    fetchOpts.body = JSON.stringify(opts.body);
  }
  const resp = await fetch(url, fetchOpts);
  const data = await resp.json();
  if (!resp.ok) {
    throw new Error(data && data.error ? data.error : `Server error (${resp.status})`);
  }
  return data;
}

/* ---------- Web Audio SFX ---------- */
let actx = null;
function tone(f, d = .12, type = 'sine', v = .06, delay = 0) {
  if (!CFG.sound) return;
  try {
    actx = actx || new (window.AudioContext || window.webkitAudioContext)();
    const o = actx.createOscillator(), g = actx.createGain(), t0 = actx.currentTime + delay;
    o.type = type; o.frequency.setValueAtTime(f, t0);
    g.gain.setValueAtTime(v, t0); g.gain.exponentialRampToValueAtTime(.0001, t0 + d);
    o.connect(g); g.connect(actx.destination); o.start(t0); o.stop(t0 + d);
  } catch (e) {}
}
const sfx = {
  swap: () => tone(330, .08, 'triangle'),
  bad: () => tone(160, .18, 'sawtooth', .04),
  match: n => { tone(440 + n * 70, .14, 'triangle'); tone(660 + n * 90, .16, 'sine', .05, .07); },
  synth: () => [523, 659, 784, 1047].forEach((f, i) => tone(f, .18, 'sine', .06, i * .07)),
  win: () => [523, 659, 784, 1047, 1319].forEach((f, i) => tone(f, .25, 'triangle', .07, i * .1)),
  lose: () => [392, 330, 262].forEach((f, i) => tone(f, .3, 'sawtooth', .04, i * .15))
};

/* ---------- Rendering ---------- */
const boardEl = $('#board');

function renderBoard(grid, o = {}) {
  if (!grid) return;
  const pop = new Set(o.pop || []), fall = o.fall, born = new Set(o.born || []), shake = new Set(o.shake || []);
  let h = '';
  for (let r = 0; r < R; r++) for (let c = 0; c < C; c++) {
    const x = grid[r][c], k = r * C + c;
    let inner = '';
    if (x) {
      const cd = CFG.candies[x.t] || CFG.candies.red || { color: '#e8334a', icon: '🍓' };
      const cls = ['candy'];
      if (x.t === 'bomb') cls.push('bomb');
      if (x.s) cls.push('s-' + x.s);
      if (pop.has(k)) cls.push('pop');
      else if (shake.has(k)) cls.push('shake');
      else if (born.has(k)) cls.push('born');
      else if (fall && fall[r][c]) cls.push('fall');
      inner = `<div class="${cls.join(' ')}" style="--cc:${cd.color};${fall && fall[r][c] ? '--f:' + fall[r][c] : ''}">${cd.icon}</div>`;
    }
    const cc = ['cell'];
    if ((r + c) % 2) cc.push('alt');
    if (sel && sel[0] === r && sel[1] === c) cc.push('sel');
    if (hint && hint.some(p => p[0] === r && p[1] === c)) cc.push('hint');
    h += `<div class="${cc.join(' ')}" data-r="${r}" data-c="${c}">${inner}</div>`;
  }
  boardEl.innerHTML = h;
}

function floatText(txt, r, c) {
  const cell = boardEl.querySelector(`.cell[data-r="${r}"][data-c="${c}"]`);
  if (!cell) return;
  const f = document.createElement('div');
  f.className = 'floaty';
  f.textContent = txt;
  cell.appendChild(f);
  setTimeout(() => f.remove(), 1000);
}

function applyHud(hud) {
  if (!S) return;
  const P = hud.player, A = hud.ai;
  $('#hName').textContent = S.name;
  $('#hLevel').textContent = S.level;
  $('#hScore').textContent = P.score.toLocaleString();
  $('#hTarget').textContent = S.target.toLocaleString();
  $('#hMoves').textContent = hud.movesLeft;
  $('#hMatches').textContent = P.matches;
  $('#sP').textContent = P.score.toLocaleString();
  $('#sA').textContent = A.score.toLocaleString();
  $('#mT').textContent = P.matches;
  $('#m2').textContent = P.m['2'];
  $('#m3').textContent = P.m['3'];
  $('#m4').textContent = P.m['4'];
  $('#m5').textContent = P.m['5'];
  if (hud.aiPanel) {
    $('#aStrat').textContent = hud.aiPanel.strategy;
    $('#aFav').textContent = hud.aiPanel.favLabel;
    $('#aLvl').textContent = hud.aiPanel.levelLabel;
  }
}

function updateHUD() {
  if (!S) return;
  applyHud({ movesLeft: S.movesLeft, player: S.player, ai: S.ai, aiPanel: S.aiPanel });
}

function setBanner(txt, cls = '') { const b = $('#banner'); b.textContent = txt; b.className = cls; }
function setSub(txt) { $('#subbar').textContent = txt; }

function addLog(msg) {
  const box = $('#log');
  const d = document.createElement('div');
  d.textContent = msg;
  box.prepend(d);
  while (box.children.length > 40) box.lastChild.remove();
}

function syncFullLog(entries) {
  const box = $('#log');
  box.innerHTML = '';
  for (const msg of (entries || [])) {
    const d = document.createElement('div');
    d.textContent = msg;
    box.appendChild(d);
  }
}

function toast(msg) {
  const t = document.createElement('div');
  t.className = 'toast';
  t.textContent = msg;
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 2600);
}

/* ---------- Animation Playback of Java Engine Steps ---------- */
async function replaySteps(steps, token) {
  for (const step of (steps || [])) {
    if (token !== runToken) return false;
    (step.logs || []).forEach(addLog);
    if (step.sfx === 'synth') sfx.synth();
    else sfx.match(step.combo || 1);
    renderBoard(step.popGrid, { pop: step.pop });
    floatText(step.floatText, step.floatRow, step.floatCol);
    if (step.hud) applyHud(step.hud);
    await sleep(300);
    if (token !== runToken) return false;
    renderBoard(step.afterGrid, { fall: step.fall, born: step.born });
    await sleep(340);
    if (token !== runToken) return false;
  }
  return true;
}

/* ---------- Board Pointer Input ---------- */
let downCell = null;
boardEl.addEventListener('pointerdown', e => {
  const cell = e.target.closest('.cell');
  if (!cell) return;
  downCell = [+cell.dataset.r, +cell.dataset.c];
});

window.addEventListener('pointerup', e => {
  if (!downCell || !S) return;
  const start = downCell;
  downCell = null;
  if (S.over || busy || S.turn !== 'player') return;
  const el = document.elementFromPoint(e.clientX, e.clientY);
  const cell = el && el.closest && el.closest('#board .cell');
  const end = cell ? [+cell.dataset.r, +cell.dataset.c] : start;
  const adj = (p, q) => Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]) === 1;
  if (end[0] !== start[0] || end[1] !== start[1]) {
    if (adj(start, end)) return playerMove(start, end);
    sel = start;
    return renderBoard(S.grid);
  }
  if (sel && adj(sel, start)) return playerMove(sel, start);
  sel = (sel && sel[0] === start[0] && sel[1] === start[1]) ? null : start;
  renderBoard(S.grid);
});

async function playerMove(a, b) {
  if (!S || S.over || busy || S.turn !== 'player') return;
  const token = runToken;
  busy = true;
  sel = null;
  hint = null;

  try {
    const res = await api('/api/game/swap', {
      method: 'POST',
      body: { sessionId: S.id, r1: a[0], c1: a[1], r2: b[0], c2: b[1] }
    });
    if (token !== runToken) return;
    if (res.ignored) {
      S = res.session;
      busy = false;
      return;
    }

    renderBoard(res.swappedGrid);
    sfx.swap();

    if (!res.valid) {
      await sleep(160);
      if (token !== runToken) return;
      renderBoard(res.swappedGrid, { shake: res.shake });
      sfx.bad();
      await sleep(150);
      if (token !== runToken) return;
      S = res.session;
      renderBoard(S.grid);
      if (res.subbar) setSub(res.subbar);
      updateHUD();
      busy = false;
      return;
    }

    const okReplay = await replaySteps(res.steps, token);
    if (!okReplay) return;

    if (res.levels) CFG.levels = res.levels;
    S = res.session;
    syncFullLog(S.log);
    updateHUD();

    if (res.targetToast) {
      sfx.win();
      toast(res.targetToast);
    }

    if (res.reshuffled) {
      toast('No moves left. Reshuffling the board.');
      renderBoard(res.reshuffledGrid, { born: Array.from({ length: 64 }, (_, i) => i) });
      await sleep(400);
      if (token !== runToken) return;
    }

    if (res.targetReachedModal) {
      sfx.win();
      showTargetReachedModal(token);
      return;
    }

    if (res.endResult) {
      showEndModal(res.endResult);
      busy = false;
      return;
    }

    if (res.needsAiTurn) {
      await runAiTurn(token);
      return;
    }

    setBanner('👤 YOUR TURN');
    setSub("Click '💡 Ask AI for Hint' for valid move assistance!");
    renderBoard(S.grid);
    busy = false;
  } catch (err) {
    toast('Error: ' + err.message);
    busy = false;
  }
}

async function runAiTurn(token) {
  setBanner("🤖 AI OPPONENT'S TURN", 'ai');
  setSub('The AI is analysing the board...');
  await sleep(700);
  if (token !== runToken) return;

  try {
    const preGrid = S.grid;
    const res = await api('/api/game/ai-turn', {
      method: 'POST',
      body: { sessionId: S.id }
    });
    if (token !== runToken) return;

    if (res.aiMove) {
      hint = res.aiMove;
      renderBoard(preGrid);
      await sleep(650);
      if (token !== runToken) return;
      hint = null;
      if (res.swapLog) addLog(res.swapLog);
      renderBoard(res.swappedGrid);
      sfx.swap();
      const okReplay = await replaySteps(res.steps, token);
      if (!okReplay) return;
      if (res.scoreLog) addLog(res.scoreLog);
    }

    if (res.reshuffled) {
      toast('No moves left. Reshuffling the board.');
      renderBoard(res.reshuffledGrid || res.session.grid, { born: Array.from({ length: 64 }, (_, i) => i) });
      await sleep(400);
      if (token !== runToken) return;
    }

    if (res.levels) CFG.levels = res.levels;
    S = res.session;
    syncFullLog(S.log);
    updateHUD();
    renderBoard(S.grid);

    if (res.endResult) {
      showEndModal(res.endResult);
      busy = false;
      return;
    }

    setBanner('👤 YOUR TURN');
    setSub("Click '💡 Ask AI for Hint' for valid move assistance!");
    busy = false;
  } catch (err) {
    toast('AI error: ' + err.message);
    busy = false;
  }
}

function showTargetReachedModal(token) {
  openModal(`<div class="modal narrow res"><p class="big">🎉</p><h2 class="gold">Target Reached!</h2>
    <p>${esc(S.name)} scored <b>${S.player.score.toLocaleString()}</b> in level ${S.level} with <b>${S.movesLeft}</b> moves to spare.</p>
    <div class="actions" style="justify-content:center;margin-top:18px">
      ${S.level < 20 ? '<button class="btn go" id="rNext">Next Level ▶</button>' : ''}
      <button class="btn" id="rKeep">Keep Playing</button>
    </div></div>`, () => {
    const n = $('#rNext');
    if (n) n.onclick = () => { closeModal(); busy = false; startGame(S.name, S.mode, S.level + 1); };
    $('#rKeep').onclick = async () => {
      closeModal();
      try {
        const res = await api('/api/game/keep-playing', { method: 'POST', body: { sessionId: S.id } });
        if (token !== runToken) return;
        S = res.session;
        updateHUD();
        renderBoard(S.grid);
        if (res.endResult) showEndModal(res.endResult);
        else {
          setBanner('👤 YOUR TURN');
          setSub("Click '💡 Ask AI for Hint' for valid move assistance!");
        }
      } finally {
        busy = false;
      }
    };
  });
}

function showEndModal(r) {
  r.won ? sfx.win() : sfx.lose();
  setBanner('🏁 LEVEL OVER', 'over');
  setSub(r.title);
  openModal(`<div class="modal narrow res"><p class="big">${r.icon}</p><h2 class="gold">${esc(r.title)}</h2><p>${esc(r.msg)}</p>
    <p>Accuracy ${r.accuracy}%. Max combo ${r.maxCombo}x.</p>
    <div class="actions" style="justify-content:center;margin-top:18px">
      ${r.canNext ? '<button class="btn go" id="rNext">Next Level ▶</button>' : ''}
      <button class="btn ${r.canNext ? '' : 'go'}" id="rAgain">Play Again</button>
      <button class="btn" id="rBI">📊 Analytics</button>
    </div></div>`, () => {
    const n = $('#rNext');
    if (n) n.onclick = () => { closeModal(); startGame(S.name, S.mode, S.level + 1); };
    $('#rAgain').onclick = () => { closeModal(); startGame(S.name, S.mode, S.level); };
    $('#rBI').onclick = () => openDashboard();
  });
}

/* ---------- Hints ---------- */
$('#bHint').onclick = async () => {
  if (!S || S.over || busy || S.turn !== 'player') return;
  try {
    const res = await api('/api/game/hint', { method: 'POST', body: { sessionId: S.id } });
    if (!res.found) { toast(res.message || 'No valid moves.'); return; }
    S = res.session;
    hint = res.hint;
    renderBoard(S.grid);
    syncFullLog(S.log);
    setSub(res.subbar);
  } catch (e) {
    toast(e.message);
  }
};

/* ---------- Research Book & AI Worth Check ---------- */
const verdictHTML = v => `<div class="verdict ${v.ok ? 'ok' : 'no'}"><b>${v.ok ? '✅ ACCEPT' : '❌ REJECT'}</b>${(v.reasons || []).map(esc).join('<br>')}</div>`;

function renderBook() {
  const q = $('#search').value.trim().toLowerCase();
  const el = $('#book');
  if (bookTab === 'candies') {
    const list = Object.values(CFG.candies).filter(c => !c.hidden && (!q || c.name.toLowerCase().includes(q)));
    el.innerHTML = `<div class="cgrid">${list.map(c =>
      `<button class="ccard ${selectedBook && selectedBook.type === 'candy' && selectedBook.id === c.id ? 'on' : ''}" data-id="${esc(c.id)}"><span class="ic">${c.icon}</span>${esc(c.name)}<em>${c.pts} pts</em></button>`
    ).join('') || '<p>No candies match.</p>'}</div>`;
    el.querySelectorAll('.ccard').forEach(b => b.onclick = () => {
      selectedBook = { type: 'candy', id: b.dataset.id };
      renderBook();
      runWorth();
    });
  } else {
    const nm = i => CFG.candies[i] ? `${CFG.candies[i].icon} ${esc(CFG.candies[i].name)}` : '?';
    const std = [
      ['Match 2', 'Pair Combo', 'Clears the pair'],
      ['Match 3', 'Standard Match', 'Clears the line'],
      ['Match 4', 'Striped Candy', 'Creates a line-clearing candy'],
      ['Match 5', 'Color Bomb', 'Swap it to wipe one colour']
    ].map(x => ({ k: x[0], t: x[1], d: x[2] }));
    let h = `<span class="k">Match rules</span>` + std.filter(x => !q || (x.k + x.t).toLowerCase().includes(q))
      .map(x => `<div class="entry"><b>${x.k}</b> → ${x.t}<br>${x.d}</div>`).join('');
    const fs = CFG.formulas.filter(f => !q || (f.name + f.ing.map(i => CFG.candies[i] ? CFG.candies[i].name : '').join(' ')).toLowerCase().includes(q));
    h += `<span class="k" style="margin:8px 0 6px">Synthesis formulas</span>` + fs.map(f =>
      `<button class="entry ${selectedBook && selectedBook.type === 'formula' && selectedBook.id === f.id ? 'on' : ''}" data-t="formula" data-id="${esc(f.id)}">${f.ing.map(nm).join(' + ')} → <b>${f.icon} ${esc(f.name)}</b> (+${f.pts} pts)</button>`
    ).join('');
    const rs = CFG.rules.filter(r => !q || r.name.toLowerCase().includes(q));
    h += `<span class="k" style="margin:8px 0 6px">Custom rules</span>` + rs.map(r =>
      `<button class="entry ${selectedBook && selectedBook.type === 'rule' && selectedBook.id === r.id ? 'on' : ''}" data-t="rule" data-id="${esc(r.id)}"><b>${esc(r.name)}</b><br>${esc(r.description || '')}</button>`
    ).join('');
    el.innerHTML = h;
    el.querySelectorAll('button.entry').forEach(b => b.onclick = () => {
      selectedBook = { type: b.dataset.t, id: b.dataset.id };
      renderBook();
      runWorth();
    });
  }
}

async function runWorth() {
  const box = $('#evalBox');
  if (!selectedBook) return;
  try {
    const res = await api('/api/worth/evaluate', {
      method: 'POST',
      body: { type: selectedBook.type, id: selectedBook.id }
    });
    box.innerHTML = `<h5>${esc(res.heading)}</h5><p>${esc(res.detail)}</p>${verdictHTML(res.verdict)}`;
  } catch (e) {
    box.innerHTML = '<p>The evaluator could not read this entry.</p>';
  }
}

$('#tabC').onclick = () => { bookTab = 'candies'; $('#tabC').classList.add('on'); $('#tabK').classList.remove('on'); renderBook(); };
$('#tabK').onclick = () => { bookTab = 'combos'; $('#tabK').classList.add('on'); $('#tabC').classList.remove('on'); renderBook(); };
$('#search').oninput = renderBook;
$('#bWorth').onclick = () => { if (!selectedBook) { toast('Pick a candy, formula or rule first.'); return; } runWorth(); };

/* ---------- Modals ---------- */
const ov = $('#ov');
function openModal(html, mount) { ov.innerHTML = html; ov.hidden = false; if (mount) mount(); }
function closeModal() { ov.hidden = true; ov.innerHTML = ''; }
ov.addEventListener('mousedown', e => { if (e.target === ov && !ov.querySelector('.res')) closeModal(); });
window.addEventListener('keydown', e => { if (e.key === 'Escape' && !ov.hidden && !ov.querySelector('.res')) closeModal(); });

function candyOptions(selectedId) {
  return Object.values(CFG.candies).filter(c => !c.hidden)
    .map(c => `<option value="${esc(c.id)}" ${selectedId === c.id ? 'selected' : ''}>${c.icon} ${esc(c.name)}</option>`).join('');
}

function openSynth() {
  openModal(`<div class="modal" role="dialog" aria-label="Candy synthesis"><div class="mh"><h2>🧪 Add Candy Synthesis (Match 2, 3, 4, or 5 Combination)</h2><button class="x" id="mx" aria-label="Close">✕</button></div>
    <div class="f"><label for="sN">Select Number of Candies in Synthesis Formula:</label><select id="sN">
      <option value="2">2 Candies Combination (Match 2: Pair Combo)</option><option value="3">3 Candies Combination (Match 3: Standard)</option>
      <option value="4">4 Candies Combination (Match 4: Striped Candy)</option><option value="5">5 Candies Combination (Match 5: Color Bomb)</option></select></div>
    <div class="f"><label>Synthesis Ingredients Formula:</label><div class="ing" id="sIng"></div></div>
    <div class="f"><label for="sName">Synthesized Candy Name:</label><input id="sName" maxlength="30" value="Sapphire Drop"></div>
    <div class="f"><label for="sIcon">Synthesized Candy Icon/Symbol (${CFG.icons.length} Unique Choices):</label><select id="sIcon">${CFG.icons.map(([i, n]) => `<option value="${i}" ${i === '💎' ? 'selected' : ''}>${i} ${esc(n)}</option>`).join('')}</select></div>
    <div class="f"><label for="sPts">Candy Score Value:</label><input id="sPts" type="number" inputmode="numeric" value="100"></div>
    <div id="sOut"></div>
    <button class="btn warn" id="sCheck" style="margin-top:4px">🔬 Run AI Worth Check (Accept / Reject)</button>
    <div class="actions"><button class="btn go" id="sAdd">Synthesize &amp; Add Candy to Game</button><button class="btn ghost" id="sCancel">Cancel</button></div></div>`, () => {
    const ingBox = $('#sIng');
    const buildIng = () => {
      const n = +$('#sN').value;
      const prev = [...ingBox.querySelectorAll('select')].map(s => s.value);
      ingBox.innerHTML = Array.from({ length: n }, (_, i) =>
        (i ? '<span class="plus">+</span>' : '') +
        `<select aria-label="Ingredient ${i + 1}">${candyOptions(prev[i] || (i < 2 ? 'blue' : CFG.baseIds[i % 6]))}</select>`
      ).join('');
    };
    buildIng();
    $('#sN').onchange = buildIng;

    const readPayload = () => ({
      sessionId: S ? S.id : null,
      name: $('#sName').value,
      icon: $('#sIcon').value,
      ing: [...ingBox.querySelectorAll('select')].map(s => s.value),
      pts: $('#sPts').value === '' ? null : parseInt($('#sPts').value, 10)
    });

    $('#sCheck').onclick = async () => {
      try {
        const res = await api('/api/formulas/check', { method: 'POST', body: readPayload() });
        $('#sOut').innerHTML = verdictHTML(res.verdict);
      } catch (e) {
        $('#sOut').innerHTML = `<div class="verdict no"><b>❌ Error</b>${esc(e.message)}</div>`;
      }
    };

    $('#sAdd').onclick = async () => {
      try {
        const res = await api('/api/formulas', { method: 'POST', body: readPayload() });
        $('#sOut').innerHTML = verdictHTML(res.verdict);
        if (!res.ok) return;
        CFG = res.config;
        if (res.session) { S = res.session; syncFullLog(S.log); }
        renderBook();
        toast(`${res.formula.icon} ${res.formula.name} added to the game.`);
        closeModal();
      } catch (e) {
        $('#sOut').innerHTML = `<div class="verdict no"><b>❌ Could not add candy</b>${esc(e.message)}</div>`;
      }
    };
    $('#mx').onclick = $('#sCancel').onclick = closeModal;
  });
}

function openRule() {
  openModal(`<div class="modal" role="dialog" aria-label="Custom rule"><div class="mh"><h2>📜 Add Custom Game Rule</h2><button class="x" id="mx" aria-label="Close">✕</button></div>
    <div class="f"><label for="rName">Rule Name:</label><input id="rName" maxlength="40" value="Grape Pair Bonus"></div>
    <div class="f"><label for="rKind">When this happens:</label><select id="rKind">
      <option value="match">A match is made</option><option value="synth">A candy is synthesized</option><option value="combo">A cascade reaches a combo level</option></select></div>
    <div class="f" id="rCandyBox"><label for="rCandy">Candy involved:</label><select id="rCandy"><option value="">Any candy</option>${candyOptions('purple')}</select></div>
    <div class="f" id="rNBox"><label for="rN">Match size or combo level (2 to 5):</label><select id="rN"><option>2</option><option>3</option><option>4</option><option>5</option></select></div>
    <div class="f"><label for="rRew">Reward:</label><select id="rRew"><option value="points">Bonus points</option><option value="moves">Extra moves</option></select></div>
    <div class="f"><label for="rVal">Reward amount:</label><input id="rVal" type="number" inputmode="numeric" value="100"></div>
    <div id="rOut"></div>
    <button class="btn warn" id="rCheck" style="margin-top:4px">🔬 Run AI Worth Check (Accept / Reject)</button>
    <div class="actions"><button class="btn go" id="rAdd">Add Rule to Game</button><button class="btn ghost" id="rCancel">Cancel</button></div></div>`, () => {
    const sync = () => {
      const k = $('#rKind').value;
      $('#rCandyBox').hidden = k === 'combo';
      $('#rNBox').hidden = k === 'synth';
    };
    $('#rKind').onchange = sync;
    sync();

    const readPayload = () => ({
      sessionId: S ? S.id : null,
      name: $('#rName').value.trim(),
      trigKind: $('#rKind').value,
      trigCandy: $('#rCandy').value || null,
      trigN: +$('#rN').value,
      rewKind: $('#rRew').value,
      rewVal: $('#rVal').value === '' ? null : parseInt($('#rVal').value, 10)
    });

    $('#rCheck').onclick = async () => {
      try {
        const res = await api('/api/rules/check', { method: 'POST', body: readPayload() });
        $('#rOut').innerHTML = verdictHTML(res.verdict);
      } catch (e) {
        $('#rOut').innerHTML = `<div class="verdict no"><b>❌ Error</b>${esc(e.message)}</div>`;
      }
    };

    $('#rAdd').onclick = async () => {
      try {
        const res = await api('/api/rules', { method: 'POST', body: readPayload() });
        $('#rOut').innerHTML = verdictHTML(res.verdict);
        if (!res.ok) return;
        CFG = res.config;
        if (res.session) { S = res.session; syncFullLog(S.log); }
        renderBook();
        toast(`Rule “${res.rule.name}” added.`);
        closeModal();
      } catch (e) {
        $('#rOut').innerHTML = `<div class="verdict no"><b>❌ Could not add rule</b>${esc(e.message)}</div>`;
      }
    };
    $('#mx').onclick = $('#rCancel').onclick = closeModal;
  });
}

$('#bSynth').onclick = openSynth;
$('#bRule').onclick = openRule;

/* ---------- Analytics & Exports ---------- */
async function openDashboard() {
  if (!S) return;
  try {
    const d = await api(`/api/dashboard?sessionId=${encodeURIComponent(S.id)}`);
    const P = d.player, A = d.ai;
    const m2 = P.m['2'], m3 = P.m['3'], m4 = P.m['4'], m5 = P.m['5'];
    const tot = m2 + m3 + m4 + m5, mx = Math.max(1, m2, m3, m4, m5);
    const bar = (l, v) => `<div class="bar"><span>${l}</span><i><u style="width:${Math.round(v * 100 / mx)}%"></u></i><b>${v}</b></div>`;
    openModal(`<div class="modal" role="dialog" aria-label="Analytics dashboard"><div class="mh"><h2>📊 Power BI Real-Time Telemetry &amp; Performance Dashboard</h2><button class="x" id="mx" aria-label="Close">✕</button></div>
      <div class="tiles">
        <div class="tile"><small>Player Score vs AI Score</small><b>${P.score.toLocaleString()} / ${A.score.toLocaleString()}</b></div>
        <div class="tile"><small>Player vs AI Moves</small><b>${P.moves} / ${A.moves}</b></div>
        <div class="tile"><small>Swap Accuracy</small><b style="color:var(--lime)">${P.accuracy}%</b></div>
        <div class="tile"><small>Match 2 / 3 / 4 / 5</small><b>${m2} / ${m3} / ${m4} / ${m5}</b></div>
        <div class="tile"><small>Max Combo Multiplier</small><b style="color:var(--gold)">${P.maxCombo}x</b></div></div>
      <div class="dash2">
        <div class="box"><h3>🎯 Match Distribution (Power BI Bar Chart)</h3>${bar('Match 2 (Pair Combo)', m2)}${bar('Match 3 (Standard)', m3)}${bar('Match 4 (Striped)', m4)}${bar('Match 5 (Color Bomb)', m5)}<small style="color:var(--mute)">${tot} matches in total</small></div>
        <div class="box"><h3>🤖 Turn Efficiency &amp; Accuracy</h3><div class="meter"><u style="width:${P.accuracy}%"></u></div>
          <span style="color:#9dffc0">Swap Precision: ${P.accuracy}% (${P.valid} valid, ${P.invalid} invalid).</span>
          <p style="color:var(--mute);margin:10px 0 0;font-size:.8rem">Target progress: ${d.targetProgress}% (${P.score.toLocaleString()} of ${d.target.toLocaleString()}). Hints used: ${d.hints}.</p></div></div>
      <h3 style="color:var(--gold);margin:0 0 8px;font-size:1rem">📋 Live Telemetry Table (player_game_data.csv)</h3>
      <div class="tbl"><table><thead><tr><th>PlayerID</th><th>SessionID</th><th>Mode</th><th>Level</th><th>Player Score</th><th>AI Score</th><th>Valid Swaps</th><th>Matches</th><th>Combo</th><th>Status</th></tr></thead><tbody>
      ${d.rows.map(r => `<tr><td>${esc(r.PlayerID)}</td><td>${esc(r.SessionID)}</td><td>${esc(r.Mode)}</td><td>${r.Level}</td><td>${r.PlayerScore}</td><td>${r.AIScore}</td><td>${r.ValidSwaps}</td><td>${r.Matches}</td><td>${esc(r.Combo)}</td><td>${esc(r.Status)}</td></tr>`).join('')}
      </tbody></table></div>
      <div class="actions"><button class="btn go" id="dCsv">📥 Export player_game_data.csv</button><button class="btn" id="dJson" style="background:linear-gradient(90deg,#17a99d,#3fbf79)">📄 Export player_game_data.json</button><button class="btn ghost" id="dClose">Close</button></div></div>`, () => {
      $('#mx').onclick = $('#dClose').onclick = closeModal;
      $('#dCsv').onclick = () => doExport('csv');
      $('#dJson').onclick = () => doExport('json');
    });
  } catch (e) {
    toast(e.message);
  }
}

async function doExport(fmt) {
  try {
    const sid = S ? `?sessionId=${encodeURIComponent(S.id)}` : '';
    const data = await api(`/api/export/${fmt}${sid}`);
    // Trigger browser download from Java endpoint
    const a = document.createElement('a');
    a.href = `/api/export/${fmt}${sid ? sid + '&' : '?'}download=1`;
    a.download = data.filename;
    document.body.appendChild(a);
    a.click();
    a.remove();

    openModal(`<div class="modal narrow" role="dialog" aria-label="Export data"><div class="mh"><h2>📤 ${esc(data.filename)}</h2><button class="x" id="mx" aria-label="Close">✕</button></div>
      <p style="margin:0 0 10px;color:var(--mute);font-size:.84rem">${data.rowCount} session row${data.rowCount > 1 ? 's' : ''}. If the browser did not save the file, copy the text and paste it into a ${fmt.toUpperCase()} file, then load it in Power BI with Get Data.</p>
      <textarea class="out" id="xOut" readonly></textarea>
      <div class="actions"><button class="btn go" id="xCopy">Copy to clipboard</button><button class="btn ghost" id="xClose">Close</button></div></div>`, () => {
      $('#xOut').value = data.content;
      $('#mx').onclick = $('#xClose').onclick = closeModal;
      $('#xCopy').onclick = async () => {
        try { await navigator.clipboard.writeText(data.content); toast('Copied.'); }
        catch (e) { $('#xOut').select(); toast('Press Ctrl+C to copy the selected text.'); }
      };
    });
  } catch (e) {
    toast(e.message);
  }
}

$('#bBI').onclick = openDashboard;
$('#bCsv').onclick = () => doExport('csv');
$('#bJson').onclick = () => doExport('json');

/* ---------- Setup, Levels, Start / Restart / Home ---------- */
async function openLevels() {
  try {
    const res = await api('/api/levels');
    CFG.levels = res.levels;
  } catch (e) {}
  openModal(`<div class="modal narrow" style="width:min(640px,100%)" role="dialog" aria-label="Select level"><div class="mh"><h2>📋 Select Level (1-20)</h2><button class="x" id="mx" aria-label="Close">✕</button></div>
    <div class="lvgrid">${CFG.levels.map(c =>
      `<button class="lv ${c.level === pickedLevel ? 'on' : ''}" data-n="${c.level}"><span class="st">${c.stars ? '★'.repeat(c.stars) : '&nbsp;'}</span>Level ${c.level}<small>${c.target.toLocaleString()} pts<br>${c.moves} moves</small></button>`
    ).join('')}</div></div>`, () => {
    $('#mx').onclick = closeModal;
    ov.querySelectorAll('.lv').forEach(b => b.onclick = () => {
      pickedLevel = +b.dataset.n;
      $('#lvBtn').textContent = `📋 SELECT LEVEL (1-20): LEVEL ${pickedLevel}`;
      closeModal();
    });
  });
}

$('#lvBtn').onclick = openLevels;
$('#sndBtn').onclick = async () => {
  try {
    const res = await api('/api/sound', { method: 'POST', body: { enabled: !CFG.sound } });
    CFG.sound = res.sound;
    $('#sndBtn').textContent = CFG.sound ? '🔊 SOUND: ON' : '🔇 SOUND: OFF';
    if (CFG.sound) tone(520, .1);
  } catch (e) {
    toast(e.message);
  }
};

async function startGame(name, mode, level) {
  runToken++;
  busy = false;
  sel = null;
  hint = null;
  try {
    const res = await api('/api/game/start', {
      method: 'POST',
      body: { name, mode, level, prevSessionId: S ? S.id : null }
    });
    CFG = res.config;
    S = res.session;
    $('#setup').hidden = true;
    $('#game').hidden = false;
    setBanner('👤 YOUR TURN');
    setSub("Click '💡 Ask AI for Hint' for valid move assistance!");
    syncFullLog(S.log);
    renderBoard(S.grid, { born: Array.from({ length: 64 }, (_, i) => i) });
    updateHUD();
    renderBook();
    window.scrollTo(0, 0);
  } catch (e) {
    toast('Could not start the game: ' + e.message);
  }
}

$('#startBtn').onclick = () => {
  const mode = document.querySelector('input[name=mode]:checked').value;
  startGame($('#pname').value, mode, pickedLevel);
};

$('#bRestart').onclick = () => { if (S) startGame(S.name, S.mode, S.level); };

$('#bHome').onclick = async () => {
  runToken++;
  busy = false;
  if (S) {
    try { await api('/api/game/abandon', { method: 'POST', body: { sessionId: S.id } }); } catch (e) {}
  }
  $('#game').hidden = true;
  $('#setup').hidden = false;
  closeModal();
};

/* ---------- Boot ---------- */
(async function boot() {
  try {
    CFG = await api('/api/config');
    $('#sndBtn').textContent = CFG.sound ? '🔊 SOUND: ON' : '🔇 SOUND: OFF';
  } catch (e) {
    toast('Failed to connect to Java server: ' + e.message);
  }
})();
