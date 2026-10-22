// Free My Projector — website behavior. Small and dependency-free.
(() => {
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;

  // Glass navigation once the page scrolls
  const nav = document.querySelector(".nav");
  const onScroll = () => nav.classList.toggle("scrolled", scrollY > 10);
  addEventListener("scroll", onScroll, { passive: true });
  onScroll();

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
