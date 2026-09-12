(() => {
  "use strict";

  const KEY_HIGH = "skyburst_high_score";
  const CYAN = [0, 245, 255];
  const MAGENTA = [255, 43, 214];
  const LIME = [184, 255, 60];
  const ORANGE = [255, 138, 61];
  const VIOLET = [120, 80, 255];

  const Screen = { TITLE: 0, PLAYING: 1, GAME_OVER: 2 };
  const EnemyKind = { TRIANGLE: 0, DIAMOND: 1, HEX: 2 };

  const canvas = document.getElementById("game");
  const ctx = canvas.getContext("2d");

  let screen = Screen.TITLE;
  let W = 360;
  let H = 640;
  let dpr = 1;

  let player = null;
  let bullets = [];
  let enemies = [];
  let meteors = [];
  let particles = [];
  let stars = [];

  let score = 0;
  let kills = 0;
  let surviveMs = 0;
  let highScore = 0;
  let gameTime = 0;
  let spawnTimer = 0;
  let meteorTimer = 0;
  let fireCooldown = 0;
  let pointerDown = false;
  let aimX = 0;
  let flashAlpha = 0;
  let titlePulse = 0;

  const keys = { left: false, right: false, fire: false };

  try {
    highScore = parseInt(localStorage.getItem(KEY_HIGH) || "0", 10) || 0;
  } catch (_) {
    highScore = 0;
  }

  function rgba(rgb, a) {
    return `rgba(${rgb[0]},${rgb[1]},${rgb[2]},${a})`;
  }

  function colorStr(rgb) {
    return `rgb(${rgb[0]},${rgb[1]},${rgb[2]})`;
  }

  function resize() {
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    // Logical portrait canvas ~9:16, fit in viewport
    const targetAspect = 9 / 16;
    let cssW, cssH;
    if (vw / vh > targetAspect) {
      cssH = vh;
      cssW = vh * targetAspect;
    } else {
      cssW = vw;
      cssH = vw / targetAspect;
    }
    // Prefer filling height on portrait phones
    if (vh >= vw) {
      cssW = vw;
      cssH = vh;
    } else {
      // landscape desktop: keep portrait playfield centered
      cssH = Math.min(vh, vw / targetAspect);
      cssW = cssH * targetAspect;
    }

    dpr = Math.min(window.devicePixelRatio || 1, 2);
    W = Math.max(280, Math.floor(cssW));
    H = Math.max(420, Math.floor(cssH));
    canvas.width = Math.floor(W * dpr);
    canvas.height = Math.floor(H * dpr);
    canvas.style.width = cssW + "px";
    canvas.style.height = cssH + "px";
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    if (!player) {
      resetWorld(true);
    } else {
      const ph = player.height;
      player.y = H - ph * 1.8;
      player.x = Math.max(player.width, Math.min(W - player.width, player.x));
      // rescale ship relative to width
      const pw = W * 0.09;
      player.width = pw;
      player.height = pw * 1.15;
      player.y = H - player.height * 1.8;
    }
    if (stars.length === 0) initStars();
  }

  function initStars() {
    stars = [];
    for (let i = 0; i < 60; i++) {
      stars.push({
        x: Math.random() * W,
        y: Math.random() * H,
        speed: 40 + Math.random() * 160,
        size: 1 + Math.random() * 2.5,
        bright: 80 + Math.floor(Math.random() * 160),
      });
    }
  }

  function resetWorld(keepScreen) {
    const pw = W * 0.09;
    const ph = pw * 1.15;
    player = {
      x: W / 2,
      y: H - ph * 1.8,
      width: pw,
      height: ph,
      vx: 0,
      top() {
        return this.y - this.height / 2;
      },
    };
    bullets = [];
    enemies = [];
    meteors = [];
    particles = [];
    score = 0;
    kills = 0;
    surviveMs = 0;
    gameTime = 0;
    spawnTimer = 0.6;
    meteorTimer = 1.8;
    fireCooldown = 0;
    pointerDown = false;
    aimX = player.x;
    flashAlpha = 0;
    if (!keepScreen) screen = Screen.PLAYING;
    if (stars.length === 0) initStars();
  }

  function difficulty() {
    return 1 + gameTime / 28;
  }

  function update(dt) {
    titlePulse += dt;
    flashAlpha = Math.max(0, flashAlpha - dt * 2.5);

    const starMul =
      screen === Screen.PLAYING ? 0.7 + difficulty() * 0.15 : 0.35;
    for (const s of stars) {
      s.y += s.speed * dt * starMul;
      if (s.y > H) {
        s.y = -2;
        s.x = Math.random() * W;
      }
    }

    if (screen === Screen.GAME_OVER) updateParticles(dt);
    else if (screen === Screen.PLAYING) updatePlaying(dt);
  }

  function updatePlaying(dt) {
    gameTime += dt;
    surviveMs = Math.floor(gameTime * 1000);
    score = Math.floor(surviveMs / 100) + kills * 25;

    const speed = W * 0.95;
    if (keys.left) aimX -= speed * dt;
    if (keys.right) aimX += speed * dt;

    const follow = 18;
    const k = Math.min(1, follow * dt);
    player.x += (aimX - player.x) * k;
    player.x = Math.max(
      player.width * 0.6,
      Math.min(W - player.width * 0.6, player.x)
    );
    player.y = H - player.height * 1.8;
    aimX = Math.max(player.width * 0.6, Math.min(W - player.width * 0.6, aimX));

    fireCooldown -= dt;
    const wantFire = pointerDown || keys.fire;
    if (wantFire && fireCooldown <= 0) {
      fire();
      fireCooldown = Math.max(0.12, 0.22 - difficulty() * 0.015);
    }

    const diff = difficulty();
    spawnTimer -= dt;
    if (spawnTimer <= 0) {
      spawnEnemyWave(diff);
      spawnTimer = Math.max(0.35, 1.35 - diff * 0.12);
    }
    meteorTimer -= dt;
    if (meteorTimer <= 0) {
      spawnMeteor(diff);
      meteorTimer = Math.max(0.7, 2.4 - diff * 0.18);
    }

    for (const b of bullets) {
      b.y += b.vy * dt;
      if (b.y < -20) b.alive = false;
    }
    for (const e of enemies) {
      e.phase += dt * 4;
      e.y += e.vy * dt;
      e.x += e.vx * dt + Math.sin(e.phase) * 18 * dt;
      if (e.y > H + 40) e.alive = false;
    }
    for (const m of meteors) {
      m.y += m.vy * dt;
      m.x += m.vx * dt;
      m.spin += m.spinSpeed * dt;
      if (m.y > H + 60 || m.x < -80 || m.x > W + 80) m.alive = false;
    }

    for (const b of bullets) {
      if (!b.alive) continue;
      for (const e of enemies) {
        if (!e.alive) continue;
        const dx = b.x - e.x;
        const dy = b.y - e.y;
        const r = e.size * 0.55 + b.radius;
        if (dx * dx + dy * dy < r * r) {
          b.alive = false;
          e.hp -= 1;
          spawnHit(e.x, e.y, CYAN);
          if (e.hp <= 0) {
            e.alive = false;
            kills++;
            const col =
              e.kind === EnemyKind.TRIANGLE
                ? MAGENTA
                : e.kind === EnemyKind.DIAMOND
                ? LIME
                : VIOLET;
            spawnBurst(e.x, e.y, col);
          }
          break;
        }
      }
    }

    for (const e of enemies) {
      if (!e.alive) continue;
      if (overlapShip(e.x, e.y, e.size * 0.45)) {
        e.alive = false;
        die();
        return;
      }
    }
    for (const m of meteors) {
      if (!m.alive) continue;
      if (overlapShip(m.x, m.y, m.radius * 0.75)) {
        die();
        return;
      }
    }

    bullets = bullets.filter((b) => b.alive);
    enemies = enemies.filter((e) => e.alive);
    meteors = meteors.filter((m) => m.alive);
    updateParticles(dt);
  }

  function overlapShip(x, y, r) {
    const dx = Math.abs(x - player.x);
    const dy = Math.abs(y - player.y);
    return dx < player.width * 0.4 + r && dy < player.height * 0.4 + r;
  }

  function fire() {
    bullets.push({
      x: player.x,
      y: player.top() - 4,
      radius: W * 0.012,
      vy: -H * 1.15,
      alive: true,
    });
    for (let i = 0; i < 3; i++) {
      particles.push({
        x: player.x,
        y: player.top(),
        vx: (Math.random() - 0.5) * 80,
        vy: -80 - Math.random() * 60,
        life: 0.2,
        maxLife: 0.2,
        color: CYAN,
        size: 3,
      });
    }
  }

  function spawnEnemyWave(diff) {
    const count =
      1 +
      Math.min(4, Math.floor(diff * 0.7)) +
      (Math.random() < 0.35 ? 1 : 0);
    const baseY = -30 - Math.random() * 40;
    for (let i = 0; i < count; i++) {
      const kind = Math.floor(Math.random() * 3);
      const size = W * (0.07 + Math.random() * 0.03);
      enemies.push({
        x: W * (0.12 + Math.random() * 0.76),
        y: baseY - i * size * 1.2,
        size,
        vy: H * (0.12 + 0.04 * diff) * (0.85 + Math.random() * 0.3),
        vx: (Math.random() - 0.5) * W * 0.08,
        kind,
        hp: kind === EnemyKind.HEX ? 2 : 1,
        phase: Math.random() * 6,
        alive: true,
      });
    }
  }

  function spawnMeteor(diff) {
    const r = W * (0.045 + Math.random() * 0.05);
    meteors.push({
      x: W * Math.random(),
      y: -r * 2,
      radius: r,
      vy: H * (0.18 + 0.05 * diff) * (0.9 + Math.random() * 0.35),
      vx: (Math.random() - 0.5) * W * 0.12,
      spin: Math.random() * 6,
      spinSpeed: (Math.random() - 0.5) * 6,
      alive: true,
    });
  }

  function spawnBurst(x, y, color) {
    for (let i = 0; i < 14; i++) {
      const a = Math.random() * Math.PI * 2;
      const sp = 80 + Math.random() * 220;
      particles.push({
        x,
        y,
        vx: Math.cos(a) * sp,
        vy: Math.sin(a) * sp,
        life: 0.35 + Math.random() * 0.35,
        maxLife: 0.7,
        color,
        size: 2.5 + Math.random() * 4,
      });
    }
  }

  function spawnHit(x, y, color) {
    for (let i = 0; i < 5; i++) {
      particles.push({
        x,
        y,
        vx: (Math.random() - 0.5) * 120,
        vy: (Math.random() - 0.5) * 120,
        life: 0.2,
        maxLife: 0.2,
        color,
        size: 2,
      });
    }
  }

  function updateParticles(dt) {
    for (const p of particles) {
      p.x += p.vx * dt;
      p.y += p.vy * dt;
      p.life -= dt;
      p.vx *= 0.98;
      p.vy *= 0.98;
    }
    particles = particles.filter((p) => p.life > 0);
  }

  function die() {
    spawnBurst(player.x, player.y, CYAN);
    spawnBurst(player.x, player.y, MAGENTA);
    flashAlpha = 0.85;
    score = Math.floor(surviveMs / 100) + kills * 25;
    if (score > highScore) {
      highScore = score;
      try {
        localStorage.setItem(KEY_HIGH, String(highScore));
      } catch (_) {}
    }
    screen = Screen.GAME_OVER;
    pointerDown = false;
  }

  function drawBackground() {
    const g = ctx.createLinearGradient(0, 0, 0, H);
    g.addColorStop(0, "rgb(4,4,18)");
    g.addColorStop(0.55, "rgb(12,8,40)");
    g.addColorStop(1, "rgb(6,18,36)");
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, W, H);

    ctx.strokeStyle = "rgba(0,245,255,0.11)";
    ctx.lineWidth = 1;
    const step = W / 8;
    for (let x = 0; x < W; x += step) {
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, H);
      ctx.stroke();
    }
    let y = (gameTime * 40) % step;
    for (; y < H; y += step) {
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(W, y);
      ctx.stroke();
    }

    for (const s of stars) {
      ctx.fillStyle = `rgba(180,220,255,${s.bright / 255})`;
      ctx.beginPath();
      ctx.arc(s.x, s.y, s.size, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  function drawPlayerShip(px, py, w, h, demo) {
    // engine glow
    const eg = ctx.createRadialGradient(px, py + h * 0.5, 0, px, py + h * 0.5, w * 0.55);
    eg.addColorStop(0, "rgba(0,245,255,0.63)");
    eg.addColorStop(1, "rgba(0,245,255,0)");
    ctx.fillStyle = eg;
    ctx.beginPath();
    ctx.arc(px, py + h * 0.5, w * 0.55, 0, Math.PI * 2);
    ctx.fill();

    ctx.beginPath();
    ctx.moveTo(px, py - h * 0.55);
    ctx.lineTo(px + w * 0.48, py + h * 0.4);
    ctx.lineTo(px + w * 0.18, py + h * 0.22);
    ctx.lineTo(px, py + h * 0.48);
    ctx.lineTo(px - w * 0.18, py + h * 0.22);
    ctx.lineTo(px - w * 0.48, py + h * 0.4);
    ctx.closePath();

    const lg = ctx.createLinearGradient(px, py - h, px, py + h);
    if (demo) {
      lg.addColorStop(0, colorStr(CYAN));
      lg.addColorStop(1, colorStr(MAGENTA));
    } else {
      lg.addColorStop(0, colorStr(CYAN));
      lg.addColorStop(0.55, "rgb(40,120,255)");
      lg.addColorStop(1, colorStr(MAGENTA));
    }
    ctx.fillStyle = lg;
    ctx.fill();
    ctx.strokeStyle = "#fff";
    ctx.lineWidth = 3;
    ctx.stroke();

    if (!demo) {
      ctx.beginPath();
      ctx.moveTo(px, py - h * 0.15);
      ctx.lineTo(px + w * 0.12, py + h * 0.05);
      ctx.lineTo(px, py + h * 0.18);
      ctx.lineTo(px - w * 0.12, py + h * 0.05);
      ctx.closePath();
      ctx.fillStyle = "rgba(184,255,60,0.86)";
      ctx.fill();
    }
  }

  function drawEnemy(e) {
    const glow =
      e.kind === EnemyKind.TRIANGLE
        ? MAGENTA
        : e.kind === EnemyKind.DIAMOND
        ? LIME
        : VIOLET;
    const rg = ctx.createRadialGradient(e.x, e.y, 0, e.x, e.y, e.size);
    rg.addColorStop(0, rgba(glow, 0.35));
    rg.addColorStop(1, rgba(glow, 0));
    ctx.fillStyle = rg;
    ctx.beginPath();
    ctx.arc(e.x, e.y, e.size * 0.85, 0, Math.PI * 2);
    ctx.fill();

    ctx.beginPath();
    if (e.kind === EnemyKind.TRIANGLE) {
      ctx.moveTo(e.x, e.y + e.size * 0.55);
      ctx.lineTo(e.x + e.size * 0.5, e.y - e.size * 0.45);
      ctx.lineTo(e.x - e.size * 0.5, e.y - e.size * 0.45);
    } else if (e.kind === EnemyKind.DIAMOND) {
      ctx.moveTo(e.x, e.y - e.size * 0.55);
      ctx.lineTo(e.x + e.size * 0.42, e.y);
      ctx.lineTo(e.x, e.y + e.size * 0.55);
      ctx.lineTo(e.x - e.size * 0.42, e.y);
    } else {
      for (let i = 0; i < 6; i++) {
        const a = (Math.PI / 3) * i - Math.PI / 6;
        const px = e.x + Math.cos(a) * e.size * 0.5;
        const py = e.y + Math.sin(a) * e.size * 0.5;
        if (i === 0) ctx.moveTo(px, py);
        else ctx.lineTo(px, py);
      }
    }
    ctx.closePath();
    ctx.fillStyle = rgba(glow, 0.82);
    ctx.fill();
    ctx.strokeStyle = "#fff";
    ctx.lineWidth = 2.5;
    ctx.stroke();
  }

  function drawMeteor(m) {
    const rg = ctx.createRadialGradient(m.x, m.y, 0, m.x, m.y, m.radius * 1.4);
    rg.addColorStop(0, "rgba(255,138,61,0.47)");
    rg.addColorStop(1, "rgba(255,138,61,0)");
    ctx.fillStyle = rg;
    ctx.beginPath();
    ctx.arc(m.x, m.y, m.radius * 1.4, 0, Math.PI * 2);
    ctx.fill();

    const spikes = 7;
    ctx.beginPath();
    for (let i = 0; i < spikes; i++) {
      const a = m.spin + (Math.PI * 2 * i) / spikes;
      const r = i % 2 === 0 ? m.radius : m.radius * 0.62;
      const px = m.x + Math.cos(a) * r;
      const py = m.y + Math.sin(a) * r;
      if (i === 0) ctx.moveTo(px, py);
      else ctx.lineTo(px, py);
    }
    ctx.closePath();
    const lg = ctx.createLinearGradient(
      m.x - m.radius,
      m.y,
      m.x + m.radius,
      m.y
    );
    lg.addColorStop(0, colorStr(ORANGE));
    lg.addColorStop(0.5, "rgb(255,60,90)");
    lg.addColorStop(1, "rgb(80,30,20)");
    ctx.fillStyle = lg;
    ctx.fill();
    ctx.strokeStyle = "rgba(255,220,160,0.78)";
    ctx.lineWidth = 2;
    ctx.stroke();
  }

  function drawEntities() {
    for (const p of particles) {
      const a = Math.max(0, Math.min(1, p.life / p.maxLife));
      ctx.fillStyle = rgba(p.color, a);
      ctx.beginPath();
      ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
      ctx.fill();
    }
    for (const m of meteors) drawMeteor(m);
    for (const e of enemies) drawEnemy(e);
    for (const b of bullets) {
      const rg = ctx.createRadialGradient(b.x, b.y, 0, b.x, b.y, b.radius * 3);
      rg.addColorStop(0, "#fff");
      rg.addColorStop(0.4, colorStr(CYAN));
      rg.addColorStop(1, "rgba(0,245,255,0)");
      ctx.fillStyle = rg;
      ctx.beginPath();
      ctx.arc(b.x, b.y, b.radius * 2.2, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "#fff";
      ctx.beginPath();
      ctx.arc(b.x, b.y, b.radius * 0.7, 0, Math.PI * 2);
      ctx.fill();
    }
    if (screen === Screen.PLAYING) {
      drawPlayerShip(player.x, player.y, player.width, player.height, false);
    }
  }

  function drawButton(cx, cy, w, h, label, accent) {
    const left = cx - w / 2;
    const top = cy - h / 2;
    const r = 18;
    ctx.fillStyle = rgba(accent, 0.2);
    roundRect(left, top, w, h, r);
    ctx.fill();
    ctx.strokeStyle = colorStr(accent);
    ctx.lineWidth = 3;
    roundRect(left, top, w, h, r);
    ctx.stroke();
    ctx.fillStyle = "#fff";
    ctx.font = `bold ${Math.floor(h * 0.45)}px monospace`;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(label, cx, cy);
  }

  function roundRect(x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function hitButton(x, y, cx, cy, w, h) {
    return (
      x >= cx - w / 2 &&
      x <= cx + w / 2 &&
      y >= cy - h / 2 &&
      y <= cy + h / 2
    );
  }

  function drawHud() {
    ctx.font = `bold ${Math.floor(W * 0.048)}px monospace`;
    ctx.textBaseline = "alphabetic";
    ctx.fillStyle = "rgb(232,240,255)";
    ctx.textAlign = "left";
    ctx.fillText("Счёт: " + score, W * 0.04, H * 0.05);
    ctx.fillStyle = "rgba(0,245,255,0.78)";
    ctx.textAlign = "right";
    ctx.fillText("Рекорд: " + highScore, W * 0.96, H * 0.05);

    ctx.font = `bold ${Math.floor(W * 0.028)}px monospace`;
    ctx.fillStyle = "rgba(255,255,255,0.2)";
    ctx.textAlign = "center";
    ctx.fillText("Влево — вправо", W * 0.5, H * 0.97);
  }

  function drawTitle() {
    const pulse = 0.85 + 0.15 * Math.sin(titlePulse * 2.5);
    ctx.textAlign = "center";
    ctx.textBaseline = "alphabetic";
    ctx.font = `bold ${Math.floor(W * 0.13)}px monospace`;
    ctx.fillStyle = rgba(CYAN, pulse);
    ctx.fillText("SKYBURST", W / 2, H * 0.28);

    ctx.strokeStyle = colorStr(MAGENTA);
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(W * 0.2, H * 0.31);
    ctx.lineTo(W * 0.8, H * 0.31);
    ctx.stroke();

    ctx.font = `bold ${Math.floor(W * 0.042)}px monospace`;
    ctx.fillStyle = "rgba(184,255,60,0.78)";
    ctx.fillText("Вертикальный аркадный шутер", W / 2, H * 0.36);

    const dw = W * 0.12;
    drawPlayerShip(W / 2, H * 0.48, dw, dw * 1.15, true);

    drawButton(W / 2, H * 0.66, W * 0.55, H * 0.08, "Старт", CYAN);

    ctx.font = `bold ${Math.floor(W * 0.05)}px monospace`;
    ctx.fillStyle = "rgb(232,240,255)";
    ctx.textAlign = "center";
    ctx.textBaseline = "alphabetic";
    ctx.fillText("Рекорд: " + highScore, W / 2, H * 0.78);

    ctx.font = `bold ${Math.floor(W * 0.032)}px monospace`;
    ctx.fillStyle = "rgba(200,210,255,0.55)";
    ctx.fillText("Держи и веди влево-вправо", W / 2, H * 0.88);
  }

  function drawGameOver() {
    ctx.fillStyle = "rgba(0,0,20,0.63)";
    ctx.fillRect(0, 0, W, H);

    ctx.textAlign = "center";
    ctx.textBaseline = "alphabetic";
    ctx.font = `bold ${Math.floor(W * 0.1)}px monospace`;
    ctx.fillStyle = colorStr(MAGENTA);
    ctx.fillText("Конец игры", W / 2, H * 0.32);

    ctx.font = `bold ${Math.floor(W * 0.055)}px monospace`;
    ctx.fillStyle = "#fff";
    ctx.fillText("Счёт: " + score, W / 2, H * 0.42);
    ctx.fillStyle = colorStr(CYAN);
    ctx.fillText("Рекорд: " + highScore, W / 2, H * 0.49);
    ctx.font = `bold ${Math.floor(W * 0.038)}px monospace`;
    ctx.fillStyle = "rgba(184,255,60,0.7)";
    ctx.fillText("Убито: " + kills, W / 2, H * 0.56);

    drawButton(W / 2, H * 0.68, W * 0.55, H * 0.08, "Ещё раз", LIME);
  }

  function render() {
    drawBackground();
    if (screen === Screen.TITLE) {
      drawTitle();
    } else if (screen === Screen.PLAYING) {
      drawEntities();
      drawHud();
    } else {
      drawEntities();
      drawGameOver();
    }
    if (flashAlpha > 0) {
      ctx.fillStyle = `rgba(255,80,180,${flashAlpha * 0.7})`;
      ctx.fillRect(0, 0, W, H);
    }
  }

  function canvasCoords(clientX, clientY) {
    const rect = canvas.getBoundingClientRect();
    const x = ((clientX - rect.left) / rect.width) * W;
    const y = ((clientY - rect.top) / rect.height) * H;
    return { x, y };
  }

  function setAimX(x) {
    aimX = Math.max(player.width * 0.6, Math.min(W - player.width * 0.6, x));
  }

  function onPointerDown(e) {
    e.preventDefault();
    const { x, y } = canvasCoords(e.clientX, e.clientY);
    if (screen === Screen.TITLE) {
      if (hitButton(x, y, W / 2, H * 0.66, W * 0.55, H * 0.08)) {
        resetWorld(false);
        screen = Screen.PLAYING;
      }
      return;
    }
    if (screen === Screen.GAME_OVER) {
      if (hitButton(x, y, W / 2, H * 0.68, W * 0.55, H * 0.08)) {
        resetWorld(false);
        screen = Screen.PLAYING;
      }
      return;
    }
    pointerDown = true;
    setAimX(x);
  }

  function onPointerMove(e) {
    if (screen !== Screen.PLAYING) return;
    if (e.buttons === 0 && e.pointerType === "mouse") return;
    e.preventDefault();
    const { x, y } = canvasCoords(e.clientX, e.clientY);
    pointerDown = true;
    setAimX(x);
  }

  function onPointerUp(e) {
    e.preventDefault();
    if (screen === Screen.PLAYING) {
      pointerDown = false;
    }
  }

  canvas.addEventListener("pointerdown", onPointerDown);
  canvas.addEventListener("pointermove", onPointerMove);
  canvas.addEventListener("pointerup", onPointerUp);
  canvas.addEventListener("pointercancel", onPointerUp);
  canvas.addEventListener("contextmenu", (e) => e.preventDefault());

  window.addEventListener("keydown", (e) => {
    if (e.code === "ArrowLeft" || e.code === "KeyA") {
      keys.left = true;
      e.preventDefault();
    }
    if (e.code === "ArrowRight" || e.code === "KeyD") {
      keys.right = true;
      e.preventDefault();
    }
    if (e.code === "Space") {
      keys.fire = true;
      e.preventDefault();
      if (screen === Screen.TITLE || screen === Screen.GAME_OVER) {
        resetWorld(false);
        screen = Screen.PLAYING;
      }
    }
    if (e.code === "Enter") {
      if (screen === Screen.TITLE || screen === Screen.GAME_OVER) {
        resetWorld(false);
        screen = Screen.PLAYING;
      }
      e.preventDefault();
    }
  });

  window.addEventListener("keyup", (e) => {
    if (e.code === "ArrowLeft" || e.code === "KeyA") keys.left = false;
    if (e.code === "ArrowRight" || e.code === "KeyD") keys.right = false;
    if (e.code === "Space") keys.fire = false;
  });

  window.addEventListener("resize", resize);
  window.addEventListener("orientationchange", () => setTimeout(resize, 100));

  let last = performance.now();
  function loop(now) {
    let dt = (now - last) / 1000;
    last = now;
    if (dt > 0.05) dt = 0.05;
    update(dt);
    render();
    requestAnimationFrame(loop);
  }

  resize();
  requestAnimationFrame(loop);
})();
