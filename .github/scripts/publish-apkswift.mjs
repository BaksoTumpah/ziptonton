#!/usr/bin/env node
/*
 * Mengirim versi baru ZipTonton ke situs ApkSwift (data Firestore koleksi "apps").
 * Dipanggil workflow setelah rilis GitHub dibuat. Situs ApkSwift membangun ulang dirinya
 * dari Firestore tiap 15 menit, jadi versi baru tampil di sana tanpa langkah lain.
 *
 * Env wajib : APK_PATH, TAG, BUILD_NUMBER, REPO (BaksoTumpah/ziptonton)
 * Env rahasia: APKSWIFT_USER, APKSWIFT_PASS (akun developer ApkSwift). Kalau kosong, langkah dilewati.
 * Env opsional: APKSWIFT_DEPLOY_TOKEN (PAT dengan izin Actions: write di repo ApkSwift; memicu pembuatan ulang situs),
 *               ICON_PATH (WebP/PNG 144px, default .github/apkswift-icon.webp), CHANGELOG, APP_NAME, APP_DESC, APP_CAT, VERSION_NAME, APKSWIFT_REFERER,
 *               IDENTITY_BASE / FIRESTORE_BASE (untuk uji lokal)
 */
import { readFileSync, statSync, existsSync } from "node:fs";
import { createHash } from "node:crypto";
import { basename } from "node:path";

const env = (k, d = "") => (process.env[k] ?? d).toString();
const PROJECT = "apkswift-4ea9e";
const API_KEY = "AIzaSyAkIQW8CF8CbtGU04XWYCatJDCjvn6oZDI";   // kunci web publik (sama dengan di situs)
const DEV_EMAIL = "dev@toko-apk.local";
const REFERER = env("APKSWIFT_REFERER", "https://apkswift.web.app/");
const IDENTITY = env("IDENTITY_BASE", "https://identitytoolkit.googleapis.com");
const FIRESTORE = env("FIRESTORE_BASE", "https://firestore.googleapis.com");
const PKG = "com.baksotumpah.ziptonton";
const APP_ID = PKG.toLowerCase().replace(/[^a-z0-9.]+/g, "-").replace(/^-+|-+$/g, "");   // sama dengan slug() di panel developer
const GH_API = env("GITHUB_API_BASE", "https://api.github.com");
const SITE_REPO = env("APKSWIFT_REPO", "BaksoTumpah/ApkSwift");
const SITE_WORKFLOW = env("APKSWIFT_WORKFLOW", "firebase-hosting-merge.yml");
const MAX_VERSIONS = 100;   // aturan Firestore: maksimal 200

const user = env("APKSWIFT_USER"), pass = env("APKSWIFT_PASS");
if (!user || !pass) {
  console.log("::notice::APKSWIFT_USER / APKSWIFT_PASS belum diisi, publikasi ke ApkSwift dilewati.");
  process.exit(0);
}
for (const k of ["APK_PATH", "TAG", "BUILD_NUMBER", "REPO"]) if (!env(k)) throw new Error("Env " + k + " kosong");

const apkPath = env("APK_PATH");
const buf = readFileSync(apkPath);
const bytes = statSync(apkPath).size;
const sha256 = createHash("sha256").update(buf).digest("hex");
const fmtSize = (b) => (b > 1048576 ? (b / 1048576).toFixed(1) + " MB" : Math.round(b / 1024) + " KB");   // format yang sama dengan panel developer

const build = env("BUILD_NUMBER");
const versionLabel = `${env("VERSION_NAME", "1.0")} (build ${build})`;
const date = new Date().toLocaleDateString("id-ID", { day: "numeric", month: "short", year: "numeric", timeZone: "Asia/Jakarta" });
const url = `https://github.com/${env("REPO")}/releases/download/${encodeURIComponent(env("TAG"))}/${encodeURIComponent(basename(apkPath))}`;
const changelog = (env("CHANGELOG").trim() || "Pembaruan build " + build).slice(0, 1500);
const entry = { v: versionLabel, url, size: fmtSize(bytes), log: `${changelog}\n\nSHA-256: ${sha256}`, date };

async function http(method, u, body, headers = {}) {
  let last;
  for (let i = 0; i < 4; i++) {
    try {
      const r = await fetch(u, {
        method,
        headers: { accept: "application/json", referer: REFERER, ...(body ? { "content-type": "application/json" } : {}), ...headers },
        body: body ? JSON.stringify(body) : undefined,
      });
      if (r.status === 404) return { status: 404 };
      const text = await r.text();
      if (!r.ok) {
        if (r.status >= 400 && r.status < 500 && r.status !== 429) throw Object.assign(new Error("HTTP " + r.status + " " + text.slice(0, 300)), { fatal: true });
        throw new Error("HTTP " + r.status);
      }
      return { status: r.status, json: text ? JSON.parse(text) : {} };
    } catch (e) {
      last = e;
      if (e.fatal) break;
      await new Promise((res) => setTimeout(res, 1500 * (i + 1)));
    }
  }
  throw last;
}

/* ---- tipe nilai Firestore (REST) ---- */
const S = (v) => ({ stringValue: String(v ?? "") });
const encEntry = (x) => ({ mapValue: { fields: { v: S(x.v), url: S(x.url), size: S(x.size), log: S(x.log), date: S(x.date) } } });
const decStr = (f, k) => (f && f[k] && f[k].stringValue) || "";
const decEntry = (m) => { const f = (m.mapValue && m.mapValue.fields) || {}; return { v: decStr(f, "v"), url: decStr(f, "url"), size: decStr(f, "size"), log: decStr(f, "log"), date: decStr(f, "date") }; };

/*
 * Situs publik ApkSwift adalah halaman statis yang dibuat ulang dari Firestore oleh workflow terjadwal di repo ApkSwift.
 * Jadwal GitHub sering telat berjam-jam, jadi tanpa langkah ini versi baru sudah tampil di panel developer (membaca
 * Firestore langsung) tetapi belum di halaman unduh biasa. Di sini workflow situs dipicu langsung setelah data ditulis.
 */
async function triggerSiteBuild() {
  const tok = env("APKSWIFT_DEPLOY_TOKEN");
  if (!tok) {
    console.log("::notice::APKSWIFT_DEPLOY_TOKEN belum diisi: halaman unduh ApkSwift baru ter-update saat jadwal build situs berjalan (bisa berjam-jam).");
    return;
  }
  try {
    const r = await fetch(`${GH_API}/repos/${SITE_REPO}/actions/workflows/${SITE_WORKFLOW}/dispatches`, {
      method: "POST",
      headers: { authorization: "Bearer " + tok, accept: "application/vnd.github+json", "x-github-api-version": "2022-11-28", "user-agent": "ziptonton-ci", "content-type": "application/json" },
      body: JSON.stringify({ ref: "main" }),
    });
    if (r.status === 204) console.log("Build situs ApkSwift dipicu, halaman unduh ter-update dalam beberapa menit.");
    else console.log(`::warning::Gagal memicu build situs ApkSwift (HTTP ${r.status}). Pastikan token punya izin Actions: Read and write di repo ${SITE_REPO}.`);
  } catch (e) {
    console.log("::warning::Gagal memicu build situs ApkSwift: " + String(e.message).slice(0, 200));
  }
}

async function main() {
  // 1. Masuk sebagai akun developer (kata sandi di Firebase = "namapengguna|katasandi", sama dengan panel developer)
  const login = await http("POST", `${IDENTITY}/v1/accounts:signInWithPassword?key=${API_KEY}`,
    { email: DEV_EMAIL, password: `${user.trim()}|${pass}`, returnSecureToken: true });
  const auth = { authorization: "Bearer " + login.json.idToken };

  const docUrl = `${FIRESTORE}/v1/projects/${PROJECT}/databases/(default)/documents/apps/${encodeURIComponent(APP_ID)}`;

  // 2. Baca daftar versi yang sudah ada (kalau aplikasi belum terdaftar di situs, dibuat otomatis)
  const cur = await http("GET", docUrl, null, auth);
  const exists = cur.status !== 404;
  const old = exists ? ((cur.json.fields?.versions?.arrayValue?.values) || []).map(decEntry) : [];

  // 3. Versi yang sama diganti, versi baru ditaruh paling atas
  const versions = [entry, ...old.filter((x) => x.v !== entry.v)].slice(0, MAX_VERSIONS);
  const fields = { versions: { arrayValue: { values: versions.map(encEntry) } }, updated: { integerValue: String(Date.now()) } };
  const mask = ["versions", "updated"];
  // Logo: hanya diisi kalau di situs masih kosong, supaya logo yang diganti manual di panel tidak tertimpa
  const iconPath = env("ICON_PATH", ".github/apkswift-icon.webp");
  const hasIcon = exists && ((cur.json.fields?.icon?.stringValue) || "") !== "";
  let iconSet = false;
  if (!hasIcon && existsSync(iconPath)) {
    const ib = readFileSync(iconPath);
    const ext = iconPath.toLowerCase().endsWith(".png") ? "png" : "webp";
    if (ib.length <= 200 * 1024) { fields.icon = S(`data:image/${ext};base64,${ib.toString("base64")}`); mask.push("icon"); iconSet = true; }
  }
  if (!exists) {
    Object.assign(fields, {
      name: S(env("APP_NAME", "ZipTonton")), pkg: S(PKG), cat: S(env("APP_CAT", "hiburan")),
      desc: S(env("APP_DESC", "Pemutar video Android yang memutar film langsung dari dalam file ZIP tanpa ekstrak.")),
    });
    mask.push("name", "pkg", "cat", "desc");
  }

  // 4. Tulis (PATCH dengan updateMask: hanya kolom yang disebut yang diubah, ikon/deskripsi hasil edit manual aman)
  const q = mask.map((m) => "updateMask.fieldPaths=" + m).join("&");
  await http("PATCH", `${docUrl}?${q}`, { fields }, auth);

  await triggerSiteBuild();
  console.log(`ApkSwift: ${exists ? "diperbarui" : "aplikasi baru dibuat"} -> ${versionLabel}, ${entry.size}, ${versions.length} versi tercatat${iconSet ? ", logo diunggah" : ""}`);
}

main().catch((e) => {
  console.log("::warning::Gagal mengirim ke ApkSwift: " + String(e.message).replace(pass, "***").slice(0, 400));
  process.exit(1);
});
