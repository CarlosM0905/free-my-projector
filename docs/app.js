// Free My Projector — website behavior. Small and dependency-free.
(() => {
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;

  // Glass navigation once the page scrolls
  const nav = document.querySelector(".nav");
  const onScroll = () => nav.classList.toggle("scrolled", scrollY > 10);
  addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  // Headings come into focus word by word, like the lens finding the picture
  if (!reduced) {
    document.querySelectorAll("h1, h2").forEach((h) => {
      if (h.querySelector(".w") || !h.textContent.trim()) return;
      const frag = document.createDocumentFragment();
      let i = 0;
      [...h.childNodes].forEach((node) => {
        if (node.nodeType === 3) {
          node.textContent.split(/(\s+)/).forEach((part) => {
            if (!part.trim()) { frag.appendChild(document.createTextNode(part)); return; }
            const w = document.createElement("span");
            w.className = "w"; w.style.setProperty("--i", i++); w.textContent = part;
            frag.appendChild(w);
          });
        } else {
          node.style && node.style.setProperty && node.style.setProperty("--i", i++);
          if (node.classList) node.classList.add("w");
          frag.appendChild(node);
        }
      });
      h.replaceChildren(frag);
      h.classList.add("focus");
      if (h.classList.contains("reveal")) h.classList.add("in");
      else h.classList.add("reveal");
    });
  }

  // The hero turns on when it first comes into view: lens, beam, lamp, and the wall takes the picture's colors
  const stage = document.querySelector(".stage");
  if (stage) {
    const amb = stage.querySelector(".ambient");
    const img = stage.querySelector(".screen .night");
    const light = () => amb && amb.style.setProperty("--amb", `url("${img.currentSrc || img.src}")`);
    if (img.complete) light(); else img.addEventListener("load", light);
    if (reduced) stage.classList.add("on");
    else new IntersectionObserver((e, o) => e.forEach((en) => {
      if (en.isIntersecting) { stage.classList.add("on"); o.disconnect(); }
    })).observe(stage);
  }

  // The hero screen tilts up into place as you scroll, like a screen being lowered
  const screen = document.querySelector(".screen");
  if (screen && !reduced) {
    const tilt = () => {
      const r = screen.getBoundingClientRect();
      const p = Math.min(1, Math.max(0, 1 - r.top / innerHeight));   // 0 below the fold, 1 near the top
      screen.style.setProperty("--tilt", (14 - 14 * p).toFixed(2) + "deg");
      screen.style.setProperty("--zoom", (0.94 + 0.06 * p).toFixed(3));
    };
    addEventListener("scroll", tilt, { passive: true });
    addEventListener("resize", tilt);
    tilt();
  }

  // Day and night: the same home screen in its light and dark themes
  const dn = document.querySelectorAll(".daynight button");
  const dayImg = document.querySelector(".screen .day");
  const nightImg = document.querySelector(".screen .night");
  let userPicked = false;
  const show = (night) => {
    nightImg.classList.toggle("off", !night);
    dayImg.classList.toggle("off", night);
    dn.forEach((b) => b.setAttribute("aria-pressed", String((b.dataset.mode === "night") === night)));
  };
  dn.forEach((b) => b.addEventListener("click", () => { userPicked = true; show(b.dataset.mode === "night"); }));
  if (!reduced && dayImg) {
    let night = true;
    setInterval(() => { if (!userPicked && !document.hidden) show((night = !night)); }, 5000);
  }

  // Control Center: the shortcuts, then the widgets row scrolled along
  const cc = document.querySelector(".cc");
  if (cc) {
    const imgs = cc.querySelectorAll(".shot img");
    const dots = cc.querySelectorAll(".cc-dots button");
    let i = 0, timer;
    const go = (n) => {
      i = n;
      imgs.forEach((im, k) => (im.style.opacity = k === i ? 1 : 0));
      dots.forEach((d, k) => d.setAttribute("aria-current", String(k === i)));
    };
    const auto = () => { clearInterval(timer); if (!reduced) timer = setInterval(() => !document.hidden && go((i + 1) % imgs.length), 4200); };
    dots.forEach((d, k) => d.addEventListener("click", () => { go(k); auto(); }));
    go(0);
    auto();
  }

  // Compare the two designs: drag, click or use the arrow keys
  document.querySelectorAll(".compare").forEach((box) => {
    let at = 50;
    const left = box.querySelector(".tag.l"), right = box.querySelector(".tag.r");
    const set = (v) => {
      at = Math.max(0, Math.min(100, v));
      box.style.setProperty("--split", at + "%");
      box.setAttribute("aria-valuenow", Math.round(at));
      // A design pushed out of view takes its label with it
      left.classList.toggle("gone", at < 14);
      right.classList.toggle("gone", at > 86);
    };
    const fromEvent = (e) => { const r = box.getBoundingClientRect(); set(((e.clientX - r.left) / r.width) * 100); };
    let dragging = false;
    box.addEventListener("pointerdown", (e) => { dragging = true; box.setPointerCapture(e.pointerId); fromEvent(e); });
    box.addEventListener("pointermove", (e) => dragging && fromEvent(e));
    box.addEventListener("pointerup", () => (dragging = false));
    box.addEventListener("keydown", (e) => {
      if (e.key === "ArrowLeft") { set(at - 5); e.preventDefault(); }
      if (e.key === "ArrowRight") { set(at + 5); e.preventDefault(); }
    });
    set(50);
  });

  // Copy buttons on the commands
  document.querySelectorAll(".code").forEach((block) => {
    const btn = block.querySelector("button");
    const label = btn.textContent;
    btn.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(block.querySelector("pre").innerText.trim());
        btn.textContent = btn.dataset.done || "Copied";
      } catch { btn.textContent = "⌘C"; }
      setTimeout(() => (btn.textContent = label), 1600);
    });
  });

  // Lyrics, live: each line fills as it's "sung", runs only while on screen
  const karaoke = document.querySelector("[data-karaoke]");
  if (karaoke && !reduced) {
    const lines = [...karaoke.querySelectorAll(".k-lines li")];
    const track = karaoke.querySelector(".k-lines");
    const view = karaoke.querySelector(".k-view");
    let at = -1, timer, playing = false;
    const step = () => {
      if (at >= 0) { lines[at].classList.remove("now", "go"); lines[at].classList.add("past"); }
      at = (at + 1) % lines.length;
      if (at === 0) lines.forEach((l) => l.classList.remove("past"));
      const li = lines[at];
      const dur = parseFloat(li.dataset.dur || 2.6);
      li.style.setProperty("--dur", dur + "s");
      li.classList.add("now");
      // center the active line
      track.style.transform = `translateY(${view.clientHeight / 2 - li.offsetTop - li.offsetHeight / 2}px)`;
      requestAnimationFrame(() => requestAnimationFrame(() => li.classList.add("go")));
      timer = setTimeout(step, dur * 1000 + 250);
    };
    const play = (on) => {
      if (on === playing) return;
      playing = on;
      if (on) step(); else clearTimeout(timer);
    };
    new IntersectionObserver((e) => e.forEach((en) => play(en.isIntersecting && !document.hidden)),
      { threshold: 0.3 }).observe(karaoke);
  }

  // Voice: the orb listens, the words type out, it answers — a short loop
  const voice = document.querySelector("[data-voice]");
  if (voice && !reduced) {
    const orb = voice.querySelector(".v-orb");
    const text = voice.querySelector(".v-text");
    const es = document.documentElement.lang === "es";
    const listening = es ? "Te escucho…" : "Listening…";
    const script = es ? [
      { say: "películas de terror", answer: "3 en tus apps →" },
      { say: "abre Netflix", answer: "Abriendo Netflix" },
      { say: "apaga en 30 minutos", answer: "Temporizador listo" },
      { say: "¿quién ganó el Mundial?", answer: "Preguntando a Google…" },
    ] : [
      { say: "horror movies", answer: "3 on your apps →" },
      { say: "open Netflix", answer: "Opening Netflix" },
      { say: "turn off in 30 minutes", answer: "Sleep timer set" },
      { say: "who won the World Cup?", answer: "Asking Google…" },
    ];
    let si = 0, raf, rms = 1, playing = false;
    const wave = () => { rms = 1 + Math.sin(performance.now() / 120) * 0.12 + Math.random() * 0.14; orb.style.setProperty("--s", rms.toFixed(2)); raf = requestAnimationFrame(wave); };
    const type = (s, cb) => {
      text.className = "v-text"; let i = 0;
      const tick = () => {
        text.innerHTML = s.slice(0, i) + '<span class="caret"></span>';
        if (i++ < s.length) setTimeout(tick, 42 + Math.random() * 40); else setTimeout(cb, 650);
      };
      tick();
    };
    const run = () => {
      if (!playing) return;
      const line = script[si % script.length];
      type(line.say, () => {
        if (!playing) return;
        text.className = "v-text answer"; text.textContent = line.answer;
        setTimeout(() => {
          if (!playing) return;
          text.className = "v-text dim"; text.textContent = listening;
          si++; setTimeout(run, 900);
        }, 1500);
      });
    };
    const play = (on) => {
      if (on === playing) return;
      playing = on;
      if (on) { wave(); text.className = "v-text dim"; text.textContent = listening; setTimeout(run, 700); }
      else { cancelAnimationFrame(raf); }
    };
    new IntersectionObserver((e) => e.forEach((en) => play(en.isIntersecting && !document.hidden)),
      { threshold: 0.35 }).observe(voice);
  }

  // Projector cards: a spotlight follows the pointer and the card tilts toward it
  if (!reduced && matchMedia("(pointer: fine)").matches) {
    document.querySelectorAll(".feats .feat").forEach((card) => {
      card.addEventListener("pointermove", (e) => {
        const r = card.getBoundingClientRect();
        const x = (e.clientX - r.left) / r.width, y = (e.clientY - r.top) / r.height;
        card.style.setProperty("--mx", (x * 100).toFixed(1) + "%");
        card.style.setProperty("--my", (y * 100).toFixed(1) + "%");
        card.style.setProperty("--tx", ((x - 0.5) * 7).toFixed(2) + "deg");
        card.style.setProperty("--ty", ((0.5 - y) * 7).toFixed(2) + "deg");
      });
      card.addEventListener("pointerleave", () => {
        card.style.setProperty("--tx", "0deg"); card.style.setProperty("--ty", "0deg");
      });
    });
  } else if (!reduced) {
    // Touch: light the card nearest the middle of the screen as you scroll
    const feats = [...document.querySelectorAll(".feats .feat")];
    if (feats.length) {
      const lit = () => {
        const mid = innerHeight / 2; let best, bd = 1e9;
        feats.forEach((f) => { const r = f.getBoundingClientRect(); const d = Math.abs(r.top + r.height / 2 - mid); if (d < bd) { bd = d; best = f; } });
        feats.forEach((f) => f.classList.toggle("lit", f === best && bd < innerHeight / 2));
      };
      addEventListener("scroll", lit, { passive: true }); lit();
    }
  }

  // Reveal on scroll
  const items = document.querySelectorAll(".reveal");
  if (reduced || !("IntersectionObserver" in window)) {
    items.forEach((el) => el.classList.add("in"));
  } else {
    const io = new IntersectionObserver((entries) => entries.forEach((en) => {
      if (en.isIntersecting) { en.target.classList.add("in"); io.unobserve(en.target); }
    }), { rootMargin: "0px 0px -8% 0px" });
    items.forEach((el) => io.observe(el));
    // Opening the page at a section (#install) can skip the observer's first report: whatever is on screen shows anyway.
    let pending = false;
    const sweep = () => {
      pending = false;
      items.forEach((el) => {
        if (el.classList.contains("in")) return;
        const r = el.getBoundingClientRect();
        if (r.top < innerHeight && r.bottom > 0) { el.classList.add("in"); io.unobserve(el); }
      });
    };
    const later = () => { if (!pending) { pending = true; requestAnimationFrame(sweep); } };
    addEventListener("load", later);
    addEventListener("hashchange", later);
    addEventListener("scroll", later, { passive: true });
    setTimeout(sweep, 400);
  }

  // The latest version, from GitHub (the page works without it)
  const ver = document.querySelectorAll("[data-version]");
  if (ver.length) {
    fetch("https://api.github.com/repos/CarlosM0905/free-my-projector/releases/latest")
      .then((r) => (r.ok ? r.json() : null))
      .then((j) => j && j.tag_name && ver.forEach((el) => (el.textContent = j.tag_name.replace(/^v/, ""))))
      .catch(() => {});
  }
})();
