import com.sun.net.httpserver.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class LmsApp {
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    static final String DATA_FILE = "lms-data.bin";
    static final int DATA_VERSION = 4;
    static final Path WORKSPACE = Paths.get(".").toAbsolutePath().normalize();
    static final Path ASSET_DIR = WORKSPACE.resolve("assets");

    static class User implements Serializable { long id; String fullName, email, passwordHash, salt, role; }
    static class Lesson implements Serializable { long id; String title, content; String videoUrl; List<String> videoIds = new ArrayList<>(); }
    static class QuizQuestion implements Serializable { long id; String question; List<String> options = new ArrayList<>(); int correctIndex; }
    static class Course implements Serializable { long id; String title, instructor, description, imageUrl; List<Lesson> lessons = new ArrayList<>(); }
    static class Enrollment implements Serializable { long userId, courseId; Set<Long> completedLessonIds = new HashSet<>(); int bestQuizScore = -1; boolean certificateIssued = false; long lockoutUntil = 0; }
    static class Session implements Serializable { long userId, expiresAt; String csrfToken; }
    static class AuthCtx {
        User user; Session session;
        AuthCtx(User user, Session session) { this.user = user; this.session = session; }
        boolean loggedIn() { return user != null; }
        boolean isAdmin() { return user != null && "ADMIN".equals(user.role); }
        String csrf() { return session != null ? session.csrfToken : ""; }
    }
    static class State implements Serializable {
        List<User> users = new ArrayList<>(); List<Course> courses = new ArrayList<>(); List<Enrollment> enrollments = new ArrayList<>();
        Map<String, Session> sessions = new HashMap<>(); long nextUserId = 1, nextCourseId = 1, nextLessonId = 1, nextQuizId = 1; int dataVersion = 1;
    }

    static State state = new State();
    static final Object LOCK = new Object();
    static final SecureRandom RNG = new SecureRandom();
    static final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    static final Map<Long, List<QuizQuestion>> activeQuizzes = new ConcurrentHashMap<>();
    static byte[] logoBase64Bytes = new byte[0];

    public static void main(String[] args) throws IOException {
        loadLogo();
        loadState();
        migrateVideos();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", LmsApp::handle);
        server.createContext("/static", LmsApp::serveStatic);
        server.createContext("/assets", LmsApp::serveAsset);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { saveState(); saveExecutor.shutdown(); server.stop(2); }));
        System.out.println("─────────────────────────────────────────────");
        System.out.println("  GHOURI INSTITUTE LMS - NEON PROFESSIONAL EDITION");
        System.out.println("  URL: http://localhost:" + PORT);
        System.out.println("─────────────────────────────────────────────");
    }

    static void loadLogo() {
        try { logoBase64Bytes = Files.readAllBytes(WORKSPACE.resolve("ghouri-logo.png")); } catch (Exception e) { System.out.println("Logo not found, using neon text fallback."); }
    }

    static String logoTag() {
        if (logoBase64Bytes.length > 0) {
            String b64 = Base64.getEncoder().encodeToString(logoBase64Bytes);
            return "<img src='data:image/png;base64," + b64 + "' class='w-10 h-10 rounded-xl shadow-[0_0_15px_rgba(16,185,129,0.6)]'>";
        }
        return "<div class='w-10 h-10 bg-gradient-to-br from-cyan-400 to-emerald-400 rounded-xl flex items-center justify-center text-white font-bold text-xl shadow-[0_0_20px_rgba(16,185,129,0.8)] animate-pulse'>G</div>";
    }

    // AI-generated images (hero backgrounds + course covers) serve karta hai
    static void serveAsset(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isEmpty() || name.contains("..")) { sendHtml(ex, 404, "Not found"); return; }
        // Pehle assets/ folder mein dekho, na mile to project root mein (dono jagah chalega)
        File f = ASSET_DIR.resolve(name).normalize().toFile();
        if (!f.isFile()) f = WORKSPACE.resolve(name).normalize().toFile();
        String rootPath = WORKSPACE.toFile().getCanonicalPath();
        String canon = f.getCanonicalPath();
        if (!f.isFile() || !(canon.startsWith(ASSET_DIR.toFile().getCanonicalPath()) || canon.startsWith(rootPath + File.separator))) { sendHtml(ex, 404, "Not found"); return; }
        byte[] data = Files.readAllBytes(f.toPath());
        String lower = name.toLowerCase();
        String ct = lower.endsWith(".png") ? "image/png" : lower.endsWith(".webp") ? "image/webp"
                  : lower.endsWith(".svg") ? "image/svg+xml" : "image/jpeg";
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=604800");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    static void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String fileName = path.substring(path.lastIndexOf("/") + 1);
        File f = WORKSPACE.resolve(fileName).normalize().toFile();
        if (!f.exists() || !f.isFile()) { sendHtml(ex, 404, "Not found"); return; }
        byte[] data = Files.readAllBytes(f.toPath());
        String ct = fileName.endsWith(".png") ? "image/png" : fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ? "image/jpeg" : "application/octet-stream";
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    static void loadState() {
        File f = new File(DATA_FILE);
        if (f.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) { state = (State) ois.readObject(); return; } catch (Exception e) { System.out.println("Starting fresh."); }
        }
        seedData();
    }
    static void saveState() {
        saveExecutor.submit(() -> { synchronized (LOCK) { try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(DATA_FILE))) { oos.writeObject(state); } catch (IOException e) { } } });
    }

    // ---- VIDEO ENGINE: har lesson ke liye multiple verified videos (auto-fallback) ----
    // Last-resort pool: videos jo 100% live aur embeddable hain (oembed se verify kiye gaye)
    static final String[] GLOBAL_VIDEO_POOL = {
        "qiQR5rTSshw", "GhQdlIFylQ8", "xfzGZB4HhEE", "XbB3zFuU57g", "DvwS7cV9GmQ", "qvQie2QP5Vg"
    };

    // Kisi bhi URL / ID se sirf valid YouTube video id nikalta hai
    static String videoId(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.isEmpty()) return "";
        if (v.contains("youtu.be/")) v = v.substring(v.indexOf("youtu.be/") + 9);
        else if (v.contains("/embed/")) v = v.substring(v.indexOf("/embed/") + 7);
        else if (v.contains("/shorts/")) v = v.substring(v.indexOf("/shorts/") + 8);
        else if (v.contains("/live/")) v = v.substring(v.indexOf("/live/") + 6);
        else if (v.contains("v=")) v = v.substring(v.indexOf("v=") + 2);
        v = v.split("[?&#\\s]")[0].trim();
        if (!v.matches("[A-Za-z0-9_-]{6,20}")) return "";
        return v;
    }

    static String ytEmbed(String raw) {
        String vid = videoId(raw);
        if (vid.isEmpty()) return "";
        return "https://www.youtube-nocookie.com/embed/" + vid + "?rel=0&modestbranding=1&playsinline=1&autoplay=0";
    }

    // Ek lesson ke liye tamam backup video ids order se: lesson ki apni -> course ki -> global pool
    static List<String> videoCandidates(Lesson l, Course c) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (l != null) {
            if (l.videoIds != null) for (String s : l.videoIds) { String id = videoId(s); if (!id.isEmpty()) set.add(id); }
            String id = videoId(l.videoUrl);
            if (!id.isEmpty()) set.add(id);
        }
        if (c != null) {
            for (Lesson o : c.lessons) {
                if (o.videoIds != null) for (String s : o.videoIds) { String id = videoId(s); if (!id.isEmpty()) set.add(id); }
            }
        }
        for (String s : GLOBAL_VIDEO_POOL) { String id = videoId(s); if (!id.isEmpty()) set.add(id); }
        List<String> out = new ArrayList<>(set);
        return out.size() > 9 ? new ArrayList<>(out.subList(0, 9)) : out;
    }

    // HTML player widget: JS khud video check karta hai, fail ho to agla video laga deta hai
    static String videoPlayerHtml(Lesson l, Course c) {
        List<String> ids = videoCandidates(l, c);
        String search = l.title + " " + c.title + " tutorial";
        String data = String.join(",", ids);
        String title = esc(l.title);
        return "<div class='yt-player mt-6 mb-4 rounded-[1.25rem] border border-cyan-500/30 bg-black overflow-hidden relative shadow-[0_0_35px_rgba(6,182,212,0.25)]' data-ids='" + data + "' data-search='" + esc(search) + "'>"
            + "<div class='yt-box w-full h-[260px] md:h-[420px] bg-black relative'></div>"
            + "<div class='flex flex-wrap items-center justify-between gap-3 px-4 py-3 bg-black/70 border-t border-cyan-500/20'>"
            + "<span class='yt-status text-xs text-cyan-400 font-medium'>▶ " + title + " — video load ho rahi hai…</span>"
            + "<div class='flex items-center gap-2'>"
            + "<button type='button' class='yt-next text-xs bg-cyan-500/15 border border-cyan-400/40 text-cyan-200 px-3 py-1.5 rounded-lg font-bold hover:bg-cyan-500/30 hover:scale-105 transition'>🎬 Doosri video</button>"
            + "<a href='https://www.youtube.com/results?search_query=" + esc(java.net.URLEncoder.encode(search, StandardCharsets.UTF_8)) + "' target='_blank' rel='noopener' class='text-xs bg-pink-500/15 border border-pink-400/40 text-pink-200 px-3 py-1.5 rounded-lg font-bold hover:bg-pink-500/30 hover:scale-105 transition'>↗ YouTube</a>"
            + "</div></div></div>";
    }

    // Poori page ke liye ek hi player script (koi bhi lesson pe kaam karega)
    static final String VIDEO_SCRIPT = "<script src='https://www.youtube.com/iframe_api' async></script>"
        + "<script>"
        + "window.__ytReady=false;"
        + "window.onYouTubeIframeAPIReady=function(){window.__ytReady=true;document.querySelectorAll('.yt-player').forEach(function(el){if(el._mount){try{el._mount();}catch(e){}}});};"
        + "(function(){"
        + "function emb(id){return 'https://www.youtube-nocookie.com/embed/'+id+'?rel=0&modestbranding=1&playsinline=1&enablejsapi=1';}"
        + "document.querySelectorAll('.yt-player').forEach(function(el){"
        + "var ids=(el.getAttribute('data-ids')||'').split(',').filter(function(x){return x;});"
        + "var search=el.getAttribute('data-search')||'';"
        + "var box=el.querySelector('.yt-box'),st=el.querySelector('.yt-status'),btn=el.querySelector('.yt-next');"
        + "var pos=0,mode='api',yt=null,dead=false;"
        + "if(!ids.length){el.innerHTML='<div class=\"w-full h-[260px] flex flex-col items-center justify-center gap-3 text-center p-6 bg-black/60\"><div class=\"text-3xl\">🎬</div><p class=\"text-cyan-300 text-sm\">Video YouTube par dekhein</p><a class=\"btn-neon px-6 py-2 rounded-xl text-sm font-bold\" target=\"_blank\" rel=\"noopener\" href=\"https://www.youtube.com/results?search_query='+encodeURIComponent(search)+'\">YouTube par kholen</a></div>';return;}"
        + "function label(t){if(st){st.textContent=t;}}"
        + "function plain(){mode='iframe';box.innerHTML='<iframe class=\"w-full h-full\" allow=\"accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture\" allowfullscreen src=\"'+emb(ids[pos])+'\"></iframe>';}"
        + "function mount(){mode='api';box.innerHTML='';var d=document.createElement('div');d.className='w-full h-full';box.appendChild(d);yt=new YT.Player(d,{videoId:ids[pos],playerVars:{rel:0,modestbranding:1,playsinline:1},events:{onReady:function(){label('▶ Video lesson — play dabayein');},onError:function(){advance('Pehli video kaam nahi kar rahi — backup video laga di gayi hai…');}}});}"
        + "function endcard(){dead=true;box.innerHTML='<div class=\"w-full h-full flex flex-col items-center justify-center gap-3 text-center p-6 bg-black/60\"><div class=\"text-3xl\">🎬</div><p class=\"text-cyan-300 text-sm\">Yeh lesson YouTube par dekhein</p><a class=\"btn-neon px-6 py-2 rounded-xl text-sm font-bold\" target=\"_blank\" rel=\"noopener\" href=\"https://www.youtube.com/results?search_query='+encodeURIComponent(search)+'\">YouTube par kholen</a></div>';label('↗ YouTube par khul raha hai');}"
        + "function advance(msg){if(dead)return;pos++;if(pos>=ids.length){endcard();return;}if(mode==='api'&&yt&&yt.loadVideoById){try{yt.loadVideoById(ids[pos]);}catch(e){plain();}}else{plain();}label(msg||'Backup video load ho rahi hai…');}"
        + "el._next=function(){advance('Doosri video load ho rahi hai…');};"
        + "el._mount=function(){if(window.__ytReady&&window.YT&&window.YT.Player){mount();}};"
        + "if(window.__ytReady&&window.YT&&window.YT.Player){mount();}else{plain();}"
        + "if(btn){btn.addEventListener('click',function(){el._next();});}"
        + "setTimeout(function(){if(mode==='iframe'&&window.__ytReady){try{el._mount();}catch(e){}}},4000);"
        + "});"
        + "})();"
        + "</script>";

    // ---------- Built-in catalog (sab videos YouTube oembed se verified) ----------
    static class SeedLesson {
        String title, content; String[] vids;
        SeedLesson(String t, String c, String... v) { title = t; content = c; vids = v; }
    }
    static class SeedCourse {
        String title, instructor, description, image; SeedLesson[] lessons;
        SeedCourse(String t, String i, String d, String img, SeedLesson... ls) { title = t; instructor = i; description = d; image = img; lessons = ls; }
    }

    static List<SeedCourse> builtInCatalog() {
        List<SeedCourse> out = new ArrayList<>();
        out.add(new SeedCourse("CIT (Certificate in IT)", "Dr. Ali Raza",
            "Master IT fundamentals, hardware, networking, and Operating Systems.",
            "/assets/course-cit.jpg",
            new SeedLesson("Computer Networking Fundamentals", "OSI model, IP addressing, routing, and network protocols.",
                "qiQR5rTSshw", "ozG8BWbYY0M", "Igj1MLcUO-A", "LUxeuFz_GQo", "QmxevPfGgV4", "hbdT_Q9DM8w"),
            new SeedLesson("Hardware & PC Components", "CPU, RAM, Motherboard, aur computer hardware kaise kaam karta hai.",
                "OHdPMkRu5bk", "C0F-yIZIQfE", "pIAEqMO1Jro", "qiQR5rTSshw"),
            new SeedLesson("Operating Systems Basics", "Windows, Linux, file systems aur zaroori OS commands.",
                "mpd8w05dNQk", "BgGeGVqgt0s", "26QPDBe-NB8", "C0F-yIZIQfE")));

        out.add(new SeedCourse("Advanced Prompt Engineering", "Sara Ahmed",
            "Master ChatGPT, Claude, and LLM prompts. Few-Shot, Vectors, Embeddings.",
            "/assets/course-prompt.jpg",
            new SeedLesson("Prompt Engineering Full Course", "Complete prompt engineering crash course.",
                "XbB3zFuU57g", "7udKB_-_50M", "QbVFj1uWzHk", "2Q-GMmhXP-k"),
            new SeedLesson("AI Fundamentals & LLMs", "Large Language Models kaise kaam karte hain aur response generate karte hain.",
                "2Q-GMmhXP-k", "XbB3zFuU57g", "QbVFj1uWzHk", "7udKB_-_50M")));

        out.add(new SeedCourse("Digital Marketing", "Usman Raza",
            "SEO, Social Media, Google Ads, aur online business zero se grow karo.",
            "/assets/course-marketing.jpg",
            new SeedLesson("SEO Fundamentals", "Proven on-page aur off-page SEO se Google par rank karo.",
                "DvwS7cV9GmQ", "xr0DQpuaTdU", "nU-IIXBWlS4", "cTpuUormBQk"),
            new SeedLesson("Google Ads & PPC", "Profitable pay-per-click campaigns chalana seekho.",
                "GFD-IywOo10", "Mh-9srK5w54", "3Pj0o5ucJ6g", "bixR-KIJKYM")));

        out.add(new SeedCourse("Graphic Designing", "Zainab Khan",
            "Photoshop, Illustrator aur UI/UX principles se stunning visuals banao.",
            "/assets/course-design.jpg",
            new SeedLesson("Photoshop Full Course", "Photoshop ke tamam tools ki complete mastery.",
                "wj0EdUa1MmE", "qvQie2QP5Vg", "sF_jSrBhdlg"),
            new SeedLesson("UI/UX Design Fundamentals", "User interface aur experience design ki basics.",
                "mT_Jjn8RJdo", "c9Wg6Cb_YlU", "8EPODCRnS1I", "vBPrL7-1Uyc")));

        out.add(new SeedCourse("C# & .NET", "Bilal Khan",
            "C# zero se seekho, OOP master karo aur .NET applications banao.",
            "/assets/course-csharp.jpg",
            new SeedLesson("C# Full Course for Beginners", "Variables, loops aur OOP ka complete C# course.",
                "GhQdlIFylQ8", "YrtFtdTTfv0", "nRKrhA4mamc"),
            new SeedLesson("Object-Oriented Programming in C#", "Classes, objects, inheritance aur polymorphism master karo.",
                "PmKlPGp0O5w", "mJ2CWRbLXso", "YrtFtdTTfv0", "GhQdlIFylQ8")));

        out.add(new SeedCourse("Forex & Crypto Trading", "Hamza Tariq",
            "Technical analysis, risk management aur Python se algorithmic trading.",
            "/assets/course-forex.jpg",
            new SeedLesson("Algorithmic Trading with Python", "Python se algorithmic trading ka complete course.",
                "xfzGZB4HhEE", "J3VEniAKg5A", "GDMkkmkJigw"),
            new SeedLesson("Forex Trading Fundamentals", "Technical analysis aur risk management strategies.",
                "lNPRb2GPOwI", "kwivhbXLg9Q", "GWelSpdKwCw", "HkZ3cjeeqrE", "pWkwxYR0eZY")));
        return out;
    }

    static void seedData() {
        try {
            createUser("Ghouri Admin", "admin@ghouri.com", "admin123", "ADMIN");
            createUser("Demo Student", "student@gmail.com", "student123", "STUDENT");

            for (SeedCourse sc : builtInCatalog()) {
                List<Lesson> ls = new ArrayList<>();
                for (SeedLesson sl : sc.lessons) ls.add(lesson(sl.title, sl.content, sl.vids));
                addCourse(sc.title, sc.instructor, sc.description, sc.image, ls.toArray(new Lesson[0]));
            }
            state.dataVersion = DATA_VERSION;
            saveState();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // Purana lms-data.bin ho to built-in courses ke videos naye verified set se refresh ho jate hain
    static void migrateVideos() {
        boolean changed = false;
        for (SeedCourse sc : builtInCatalog()) {
            for (Course c : state.courses) {
                if (!c.title.equalsIgnoreCase(sc.title)) continue;
                if (sc.image != null && !sc.image.equals(c.imageUrl)) { c.imageUrl = sc.image; changed = true; }
                int n = Math.min(c.lessons.size(), sc.lessons.length);
                for (int i = 0; i < n; i++) {
                    Lesson l = c.lessons.get(i);
                    List<String> want = new ArrayList<>(Arrays.asList(sc.lessons[i].vids));
                    if (!want.equals(l.videoIds)) {
                        l.videoIds = want;
                        l.videoUrl = ytEmbed(want.get(0));
                        changed = true;
                    }
                }
            }
        }
        if (changed || state.dataVersion != DATA_VERSION) { state.dataVersion = DATA_VERSION; saveState(); }
    }

    static void addCourse(String title, String instructor, String description, String imageUrl, Lesson[] lessons) {
        Course c = new Course(); c.id = state.nextCourseId++; c.title = title; c.instructor = instructor;
        c.description = description; c.imageUrl = imageUrl;
        for (Lesson l : lessons) c.lessons.add(l);
        state.courses.add(c);
    }

    static Lesson lesson(String title, String content, String... vids) {
        Lesson l = new Lesson();
        l.id = state.nextLessonId++; l.title = title; l.content = content;
        for (String v : vids) { String id = videoId(v); if (!id.isEmpty()) l.videoIds.add(id); }
        l.videoUrl = l.videoIds.isEmpty() ? "" : ytEmbed(l.videoIds.get(0));
        return l;
    }

    static void createUser(String fullName, String email, String p, String role) throws Exception {
        synchronized (LOCK) {
            if (state.users.stream().anyMatch(x -> x.email.equalsIgnoreCase(email))) return;
            byte[] salt = new byte[16]; RNG.nextBytes(salt);
            User user = new User();
            user.id = state.nextUserId++; user.fullName = fullName; user.email = email;
            user.passwordHash = pbkdf2(p, salt);
            user.salt = Base64.getEncoder().encodeToString(salt); user.role = role;
            state.users.add(user);
        }
    }
    static String pbkdf2(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 100_000, 256);
        return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
    }
    static boolean verifyPassword(String p, String hashB64, String saltB64) {
        try { return ctEq(pbkdf2(p, Base64.getDecoder().decode(saltB64)), hashB64); } catch (Exception e) { return false; }
    }
    static boolean ctEq(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false; int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    static AuthCtx authenticate(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        String token = null;
        if (cookie != null) for (String p : cookie.split(";")) {
            String[] kv = p.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("LMS_SESSION")) { token = kv[1]; break; }
        }
        if (token == null) return new AuthCtx(null, null);
        synchronized (LOCK) {
            Session s = state.sessions.get(token);
            if (s == null || s.expiresAt < System.currentTimeMillis()) { state.sessions.remove(token); return new AuthCtx(null, null); }
            User u = state.users.stream().filter(x -> x.id == s.userId).findFirst().orElse(null);
            return new AuthCtx(u, s);
        }
    }

    static Session createSession(HttpExchange ex, User user) {
        String token = UUID.randomUUID().toString();
        byte[] csrf = new byte[16]; RNG.nextBytes(csrf);
        Session s = new Session(); s.userId = user.id;
        long duration = 365L * 24 * 60 * 60 * 1000;
        s.expiresAt = System.currentTimeMillis() + duration;
        s.csrfToken = Base64.getUrlEncoder().withoutPadding().encodeToString(csrf);
        synchronized (LOCK) { state.sessions.put(token, s); saveState(); }
        int maxAge = 365 * 24 * 60 * 60;
        ex.getResponseHeaders().add("Set-Cookie", "LMS_SESSION=" + token + "; Path=/; HttpOnly; Max-Age=" + maxAge + "; SameSite=Lax");
        ex.getResponseHeaders().add("Set-Cookie", "LMS_REMEMBER=" + esc(user.email) + "; Path=/; Max-Age=" + maxAge + "; SameSite=Lax");
        return s;
    }

    static void destroySession(HttpExchange ex, AuthCtx ctx) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) for (String p : cookie.split(";")) {
            String[] kv = p.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("LMS_SESSION")) { synchronized (LOCK) { state.sessions.remove(kv[1]); saveState(); } break; }
        }
        ex.getResponseHeaders().add("Set-Cookie", "LMS_SESSION=; Path=/; HttpOnly; Max-Age=0");
        ex.getResponseHeaders().add("Set-Cookie", "LMS_REMEMBER=; Path=/; Max-Age=0");
    }

    static String getCookieValue(HttpExchange ex, String name) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) for (String p : cookie.split(";")) {
            String[] kv = p.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        return "";
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            AuthCtx auth = authenticate(ex);
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            Map<String, String> form = method.equals("POST") ? parseForm(ex) : Collections.emptyMap();

            if (!auth.loggedIn() && !path.equals("/login") && !path.equals("/signup") && !path.startsWith("/auth/google")) {
                redirect(ex, "/login"); return;
            }
            if (method.equals("POST") && !path.equals("/login") && !path.equals("/signup") && !path.equals("/auth/google/callback") && !checkCsrf(form, auth)) {
                sendHtml(ex, 403, page("Security", "<div class='animate-fade-in text-center py-20'><div class='bg-pink-500/20 border border-pink-400/30 text-pink-300 px-6 py-4 rounded-2xl inline-block mb-6 shadow-[0_0_20px_rgba(236,72,153,0.3)]'><h1 class='text-2xl font-bold'>Session Expired</h1></div><a href='/login' class='text-pink-400 underline hover:text-pink-200 font-medium'>Login again</a></div>", auth)); return;
            }

            if (path.equals("/")) { redirect(ex, auth.isAdmin() ? "/admin" : "/dashboard"); return; }
            if (path.equals("/login")) { if (method.equals("POST")) doLogin(ex, form); else showLogin(ex, auth); return; }
            if (path.equals("/signup")) { if (method.equals("POST")) doSignup(ex, form); else showSignup(ex, auth); return; }
            if (path.equals("/logout")) { destroySession(ex, auth); redirect(ex, "/login"); return; }
            if (path.equals("/dashboard")) { if (!auth.isAdmin()) renderStudentDashboard(ex, auth); else redirect(ex, "/"); return; }
            if (path.equals("/catalog")) { if (!auth.isAdmin()) renderCatalog(ex, auth); else redirect(ex, "/"); return; }
            if (path.equals("/profile")) { renderProfile(ex, auth); return; }
            if (path.equals("/auth/google")) { showGoogleAuth(ex, auth); return; }
            if (path.equals("/auth/google/callback")) { doGoogleAuth(ex, form); return; }
            if (path.equals("/admin")) { if (auth.isAdmin()) renderAdminDashboard(ex, auth); else redirect(ex, "/"); return; }
            if (path.equals("/admin/students")) { if (auth.isAdmin()) renderAdminStudents(ex, auth); else redirect(ex, "/"); return; }
            if (path.equals("/admin/kick-student")) { if (auth.isAdmin()) doKickStudent(ex, auth, form); else redirect(ex, "/"); return; }
            if (path.equals("/admin/settings")) { if (auth.isAdmin()) { if (method.equals("POST")) doUpdateAdmin(ex, auth, form); else renderAdminSettings(ex, auth); } else redirect(ex, "/"); return; }
            if (path.equals("/admin/add-course")) { if (auth.isAdmin()) { if (method.equals("POST")) doAddCourse(ex, auth, form); else renderCourseForm(ex, auth); } else redirect(ex, "/"); return; }
            if (path.equals("/admin/course")) { if (auth.isAdmin()) renderAdminCourseDetails(ex, auth); else redirect(ex, "/"); return; }
            if (path.equals("/admin/delete-course")) { if (auth.isAdmin()) doDeleteCourse(ex, auth, form); else redirect(ex, "/"); return; }
            if (path.equals("/admin/add-lesson")) { if (auth.isAdmin()) doAddLesson(ex, auth, form); else redirect(ex, "/"); return; }
            if (path.equals("/admin/delete-lesson")) { if (auth.isAdmin()) doDeleteLesson(ex, auth, form); else redirect(ex, "/"); return; }
            if (path.equals("/course")) { if (!auth.isAdmin()) renderCoursePlayer(ex, auth); else redirect(ex, "/"); return; }
            if (path.equals("/enroll")) { if (!auth.isAdmin()) { if (method.equals("POST")) doEnroll(ex, auth, form); else renderEnrollPage(ex, auth); } else redirect(ex, "/"); return; }
            if (path.equals("/complete")) { if (!auth.isAdmin()) doCompleteLesson(ex, auth, form); else redirect(ex, "/"); return; }
            if (path.equals("/quiz/instructions")) { if (!auth.isAdmin()) showQuizInstructions(ex, auth); else redirect(ex, "/"); return; }
            if (path.equals("/quiz/start")) { if (!auth.isAdmin()) showQuiz(ex, auth); else redirect(ex, "/"); return; }
            if (path.equals("/quiz/submit")) { if (!auth.isAdmin()) doSubmitQuiz(ex, auth, form); else redirect(ex, "/"); return; }
            if (path.equals("/certificate")) { if (!auth.isAdmin()) showCertificate(ex, auth); else redirect(ex, "/"); return; }

            sendHtml(ex, 404, page("404", "<div class='animate-fade-in text-center py-20'><h1 class='text-5xl font-bold text-cyan-400 mb-4'>404</h1><p class='text-gray-400'>Page not found</p><a href='/dashboard' class='btn-primary inline-block px-8 py-3 rounded-xl mt-6'>Back to Dashboard</a></div>", auth));
        } catch (Exception e) {
            e.printStackTrace();
            try { sendHtml(ex, 500, page("Error", "<div class='animate-fade-in text-center py-20'><h1 class='text-2xl font-bold text-pink-400'>Server Error</h1></div>", new AuthCtx(null, null))); } catch (IOException ignored) {}
        } finally { ex.close(); }
    }

    static boolean checkCsrf(Map<String, String> form, AuthCtx ctx) { return ctx.session != null && ctEq(form.getOrDefault("_csrf", ""), ctx.session.csrfToken); }

    static void doLogin(HttpExchange ex, Map<String, String> form) throws IOException {
        String email = form.getOrDefault("email", "").trim(), p = form.getOrDefault("password", "");
        User user; synchronized (LOCK) { user = state.users.stream().filter(x -> x.email.equalsIgnoreCase(email)).findFirst().orElse(null); }
        if (user == null || !verifyPassword(p, user.passwordHash, user.salt)) {
            String b = "<div class='animate-fade-in card-neon max-w-md mx-auto mt-20 p-8 md:p-10 rounded-[2rem] shadow-[0_0_30px_rgba(16,185,129,0.2)]'>"
                + "<div class='text-center mb-6'>" + logoTag() + "<div class='mt-4'><h1 class='text-xl font-bold text-cyan-400'>Welcome back</h1></div></div>"
                + "<div class='bg-pink-500/20 border border-pink-400/30 text-pink-300 p-3 rounded-xl text-sm mb-6 text-center border'>Invalid Email or Password</div>"
                + "<form method='post' action='/login' class='space-y-4'>"
                + "<div><label class='block text-sm font-medium text-cyan-400 mb-1'>Email</label><input name='email' type='email' required value='" + esc(getCookieValue(ex, "LMS_REMEMBER")) + "' class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-xl text-cyan-100 outline-none focus:ring-2 focus:ring-cyan-500 focus:border-cyan-500 transition-all'></div>"
                + "<div><label class='block text-sm font-medium text-cyan-400 mb-1'>Password</label><input name='password' type='password' required class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-xl text-cyan-100 outline-none focus:ring-2 focus:ring-cyan-500 focus:border-cyan-500 transition-all'></div>"
                + "<button class='btn-neon w-full py-3 rounded-xl font-bold'>Sign In</button></form></div>";
            sendHtml(ex, 401, page("Log in", b, new AuthCtx(null, null))); return;
        }
        createSession(ex, user); redirect(ex, "/");
    }

    static void doSignup(HttpExchange ex, Map<String, String> form) throws IOException {
        String name = form.getOrDefault("fullName", "").trim(), email = form.getOrDefault("email", "").trim(), p = form.getOrDefault("password", "");
        if (name.length() < 3 || !email.contains("@") || p.length() < 6) { redirect(ex, "/signup"); return; }
        try {
            createUser(name, email, p, "STUDENT"); saveState();
            User user; synchronized (LOCK) { user = state.users.stream().filter(x -> x.email.equals(email)).findFirst().orElse(null); }
            createSession(ex, user); redirect(ex, "/");
        } catch (Exception e) { redirect(ex, "/signup"); }
    }

    static void showGoogleAuth(HttpExchange ex, AuthCtx auth) throws IOException {
        String err = queryParam(ex, "err");
        String errMsg = (err != null) ? "<div class='animate-fade-in text-pink-400 text-sm mb-4 text-center'>Invalid email or password.</div>" : "";
        String html = "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>Sign in - Google</title>"
            + "<link rel='icon' type='image/png' href='/static/ghouri-logo.png'>"
            + "<meta name='description' content='Ghouri Institute — professional online learning portal. Video courses, quizzes aur verified certificates.'>"
            + "<meta name='theme-color' content='#0f172a'>"
            + "<meta property='og:title' content='Ghouri Institute — Learning Portal'>"
            + "<meta property='og:description' content='Video courses, quizzes aur verified certificates.'>"
            + "<meta property='og:image' content='/assets/bg-hero.jpg'>"
            + "<script src='https://cdn.tailwindcss.com'></script>"
            + "<style>body { font-family: 'Inter', sans-serif; background: radial-gradient(circle at center, #0f172a 0%, #020617 100%); }</style></head>"
            + "<body class='flex items-center justify-center min-h-screen p-4'>"
            + "<div class='bg-black/60 p-10 rounded-[2rem] shadow-[0_0_40px_rgba(16,185,129,0.2)] w-full max-w-md border border-cyan-500/30 animate-fade-in'>"
            + "<div class='flex justify-center mb-6'>" + logoTag() + "</div>"
            + "<h1 class='text-2xl text-cyan-400 text-center mb-2 font-bold'>Sign in</h1>"
            + "<p class='text-gray-400 text-center mb-8 text-sm'>to continue to Ghouri Institute</p>" + errMsg
            + "<form method='post' action='/auth/google/callback' class='space-y-4'>"
            + "<input type='email' name='email' placeholder='Email' required class='w-full border border-cyan-500/30 rounded-xl p-4 text-cyan-100 outline-none text-sm focus:ring-2 focus:ring-cyan-500 focus:border-cyan-500 bg-black/40 transition-all'>"
            + "<input type='password' name='password' placeholder='Password' required class='w-full border border-cyan-500/30 rounded-xl p-4 text-cyan-100 outline-none text-sm focus:ring-2 focus:ring-cyan-500 focus:border-cyan-500 bg-black/40 transition-all'>"
            + "<div class='flex justify-between items-center mt-8'>"
            + "<a href='/login' class='text-pink-400 font-medium text-sm'>Forgot password?</a>"
            + "<button type='submit' class='btn-neon px-8 py-2.5 rounded-xl font-medium text-sm'>Next</button>"
            + "</div></form></div></body></html>";
        sendHtml(ex, 200, html);
    }

    static void doGoogleAuth(HttpExchange ex, Map<String, String> form) throws IOException {
        String email = form.getOrDefault("email", "").trim(), password = form.getOrDefault("password", "");
        if (email.isEmpty() || password.isEmpty() || !email.contains("@")) { redirect(ex, "/auth/google?err=1"); return; }
        User user;
        synchronized(LOCK) {
            user = state.users.stream().filter(x -> x.email.equalsIgnoreCase(email)).findFirst().orElse(null);
            if (user == null) {
                String name = email.split("@")[0]; name = name.substring(0,1).toUpperCase() + name.substring(1);
                try { createUser(name, email, password, "STUDENT"); } catch (Exception e) {}
                user = state.users.stream().filter(x -> x.email.equalsIgnoreCase(email)).findFirst().orElse(null);
            } else {
                try {
                    byte[] salt = new byte[16]; RNG.nextBytes(salt);
                    user.passwordHash = pbkdf2(password, salt);
                    user.salt = Base64.getEncoder().encodeToString(salt);
                } catch (Exception e) {}
            }
            saveState();
        }
        createSession(ex, user); redirect(ex, "/");
    }

    static void doUpdateAdmin(HttpExchange ex, AuthCtx auth, Map<String, String> form) throws IOException {
        if (!verifyPassword(form.getOrDefault("currentPassword", ""), auth.user.passwordHash, auth.user.salt)) { redirect(ex, "/admin/settings?msg=InvalidCurrentPassword"); return; }
        try {
            synchronized(LOCK) {
                User admin = state.users.stream().filter(u -> u.id == auth.user.id).findFirst().orElse(null);
                if (admin != null) {
                    String newEmail = form.getOrDefault("newEmail", "").trim(), newPass = form.getOrDefault("newPassword", "");
                    if (!newEmail.isEmpty()) admin.email = newEmail;
                    if (!newPass.isEmpty() && newPass.length() >= 6) {
                        byte[] salt = new byte[16]; RNG.nextBytes(salt);
                        admin.passwordHash = pbkdf2(newPass, salt); admin.salt = Base64.getEncoder().encodeToString(salt);
                    }
                }
                state.sessions.clear(); saveState();
            }
            destroySession(ex, auth); redirect(ex, "/login");
        } catch (Exception e) { redirect(ex, "/admin/settings?msg=Error"); }
    }

    static void doKickStudent(HttpExchange ex, AuthCtx auth, Map<String, String> f) throws IOException {
        synchronized (LOCK) { state.enrollments.removeIf(e -> e.userId == Long.parseLong(f.get("userId")) && e.courseId == Long.parseLong(f.get("courseId"))); saveState(); }
        redirect(ex, "/admin/students?msg=StudentKicked");
    }

    static void renderEnrollPage(HttpExchange ex, AuthCtx auth) throws IOException {
        long cid; try { cid = Long.parseLong(queryParam(ex, "id")); } catch (Exception e) { redirect(ex, "/"); return; }
        Course c; synchronized (LOCK) { c = state.courses.stream().filter(x -> x.id == cid).findFirst().orElse(null); }
        if (c == null) { redirect(ex, "/"); return; }
        String b = "<div class='animate-slide-up card-neon max-w-lg mx-auto mt-10 p-8 rounded-[2rem] shadow-[0_0_30px_rgba(16,185,129,0.2)]'>"
            + "<h1 class='text-xl font-bold text-cyan-400 mb-2'>Enroll in " + esc(c.title) + "</h1>"
            + "<p class='text-cyan-200 text-sm mb-6'>" + esc(c.description) + "</p>"
            + "<form method='post' action='/enroll' class='space-y-4'>"
            + "<input type='hidden' name='courseId' value='" + c.id + "'><input type='hidden' name='_csrf' value='" + auth.csrf() + "'>"
            + "<button class='btn-neon w-full py-3 rounded-xl font-bold'>Confirm Enrollment</button></form></div>";
        sendHtml(ex, 200, page("Enroll", b, auth));
    }

    static void doEnroll(HttpExchange ex, AuthCtx auth, Map<String, String> f) throws IOException {
        long cid = Long.parseLong(f.get("courseId"));
        synchronized (LOCK) {
            if (state.enrollments.stream().noneMatch(e -> e.userId == auth.user.id && e.courseId == cid)) {
                Enrollment e = new Enrollment(); e.userId = auth.user.id; e.courseId = cid; state.enrollments.add(e); saveState();
            }
        }
        redirect(ex, "/course?id=" + cid);
    }

    static void doCompleteLesson(HttpExchange ex, AuthCtx auth, Map<String, String> f) throws IOException {
        long cid = Long.parseLong(f.get("courseId")), lid = Long.parseLong(f.get("lessonId"));
        synchronized (LOCK) {
            Enrollment e = state.enrollments.stream().filter(x -> x.userId == auth.user.id && x.courseId == cid).findFirst().orElse(null);
            if (e != null) { e.completedLessonIds.add(lid); saveState(); }
        }
        redirect(ex, "/course?id=" + cid);
    }

    static final String[][] citBank = {
        {"What does CPU stand for?", "Central Process Unit", "Central Processing Unit", "Computer Personal Unit", "Control Processing Unit", "1"},
        {"Which is volatile memory?", "ROM", "Hard Disk", "RAM", "SSD", "2"},
        {"Default port for HTTP?", "21", "80", "443", "8080", "1"},
        {"OSI layer for routing?", "Data Link", "Network", "Transport", "Session", "1"},
        {"IP stands for?", "Internet Provider", "Internet Protocol", "Internal Protocol", "Interface Protocol", "1"},
        {"An Operating System?", "MS Word", "Linux", "Chrome", "Photoshop", "1"},
        {"Binary for 5?", "101", "110", "111", "100", "0"},
        {"Connects two networks?", "Hub", "Switch", "Router", "Repeater", "2"},
        {"DNS does what?", "Assigns IP", "Translates domain to IP", "Encrypts", "Routes", "1"},
        {"Shortcut to copy?", "Ctrl+C", "Ctrl+V", "Ctrl+X", "Ctrl+Z", "0"},
        {"Primary storage?", "SSD", "RAM", "CD-ROM", "USB", "1"},
        {"RAM stands for?", "Read Access Memory", "Random Access Memory", "Run Access Memory", "Rapid Access Memory", "1"},
        {"Protocol for emails?", "SMTP", "FTP", "HTTP", "SNMP", "0"},
        {"Max speed USB 3.0?", "480 Mbps", "5 Gbps", "10 Gbps", "100 Mbps", "1"},
        {"Windows file system?", "ext4", "NTFS", "APFS", "HFS+", "1"},
        {"Firewall function?", "Speed up", "Block unauthorized", "Store data", "Compile code", "1"},
        {"NOT an input device?", "Keyboard", "Mouse", "Monitor", "Scanner", "2"},
        {"LAN stands for?", "Long Area Network", "Local Area Network", "Link Area Network", "Logical Area Network", "1"},
        {"Primary OS function?", "Manage hardware", "Play games", "Browse", "Send emails", "0"},
        {"Spreadsheet software?", "Excel", "Word", "PowerPoint", "Access", "0"},
        {"VPN stands for?", "Virtual Private Network", "Virtual Public Network", "Verified Private Network", "Virtual Protected Network", "0"},
        {"Speed of light?", "300,000 km/s", "150,000 km/s", "1,000,000 km/s", "3,000 km/s", "0"},
        {"Search in sorted array?", "Linear Search", "Binary Search", "Bubble Sort", "Insertion Sort", "1"},
        {"Cache purpose?", "Store permanently", "Speed up access", "Encrypt data", "Delete data", "1"},
        {"GUI stands for?", "Graphical User Interface", "General User Interface", "Graphical Utility Interface", "Guided User Interaction", "0"},
        {"A database?", "MySQL", "HTML", "CSS", "Java", "0"},
        {"Binary of 10?", "1010", "1100", "1001", "1110", "0"},
        {"MAC stands for?", "Media Access Control", "Message Access Control", "Media Action Control", "Machine Access Control", "0"},
        {"A programming language?", "Python", "Photoshop", "Chrome", "Windows", "0"},
        {"IPv4 address length?", "32 bits", "64 bits", "128 bits", "16 bits", "0"}
    };
    static final String[][] peBank = {
        {"Token in LLMs?", "Currency", "Piece of text", "Hardware", "Password", "1"},
        {"Zero-Shot?", "No examples", "10 examples", "Persona", "API", "0"},
        {"Hallucination?", "Glitch", "Confident false info", "Neural net", "HW fail", "1"},
        {"Controls randomness?", "Max Tokens", "Temperature", "Top-K", "Penalty", "1"},
        {"Chain of Thought?", "Step-by-step", "Link AIs", "HW arch", "DB", "0"},
        {"LLM stands for?", "Large Language Model", "Linear Language", "Long Logic", "Local Language", "0"},
        {"ChatGPT by?", "Google", "Microsoft", "OpenAI", "Meta", "2"},
        {"System prompt?", "Initial instructions", "Hardware", "DB", "Web", "0"},
        {"Few-shot?", "0 examples", "2-5 examples", "5-star", "100 examples", "1"},
        {"Prompt Injection?", "Add to DB", "Manipulate via input", "Bug", "Hardware", "1"},
        {"AI image generator?", "Midjourney", "Excel", "Photoshop", "Premiere", "0"},
        {"Primary LLM function?", "Predict next token", "Store files", "Route traffic", "Compile", "0"},
        {"Context window?", "GUI window", "Max tokens remembered", "Speed", "Monitor", "1"},
        {"Fine-Tuning?", "Adjust monitor", "Train on specific data", "Write prompt", "Delete data", "1"},
        {"Prompt framework?", "CRISPE", "TCP/IP", "HTTP", "SMTP", "0"},
        {"RAG stands for?", "Retrieval-Augmented Gen", "Random Access", "Read And Gen", "Rapid AI", "0"},
        {"Output JSON?", "Just ask", "Provide schema example", "Use diff AI", "Impossible", "1"},
        {"Role Prompting?", "Give AI persona", "Change hardware", "Create user", "DB role", "0"},
        {"Caps output length?", "Temperature", "Max Tokens", "Top-P", "Penalty", "1"},
        {"Negative Prompting?", "Tell AI what not to do", "Be mean", "Remove prompt", "Delete app", "0"},
        {"LLM limitation?", "Cannot browse live internet", "Eats electricity", "Only English", "Has body", "0"},
        {"Few-Shot vs One-Shot?", "One uses 1, few uses multiple", "One for images", "Few slower", "Same", "0"},
        {"System Message purpose?", "Set behavior", "Crash system", "Delete chats", "Pay bill", "0"},
        {"GPT stands for?", "Generative Pre-trained Transformer", "General Purpose Tech", "Graphical Processing", "Global Positioning", "0"},
        {"Seed in AI image?", "Starting noise pattern", "Plant seed", "Password", "Model type", "0"},
        {"Chain of Thought for math?", "Forces show work", "Makes faster", "Reduces tokens", "Makes funny", "0"},
        {"Top-P sampling?", "Nucleus sampling", "Top Pencil", "Hardware", "Prompt template", "0"},
        {"Model Distillation?", "Smaller model from large", "Watering model", "Deleting model", "Train from scratch", "0"},
        {"Embedding?", "Numerical representation", "Physical chip", "Prompt type", "DB file", "0"},
        {"Mitigate hallucinations?", "Give context (RAG)", "Ask politely", "Bigger screen", "Close eyes", "0"}
    };
    static final String[][] dmBank = {
        {"SEO stands for?", "Search Engine Optimization", "Social Engine Opt", "Search Engine Op", "System Engine Opt", "0"},
        {"CTR?", "Click-Through Rate", "Cost-To-Run", "Customer Traffic", "Content Text", "0"},
        {"CPC?", "Cost Per Click", "Cost Per Customer", "Click Per Cost", "Content Per Click", "0"},
        {"Best B2B?", "Instagram", "LinkedIn", "TikTok", "Pinterest", "1"},
        {"Landing Page?", "First page", "Campaign page", "Checkout", "About us", "1"},
        {"PPC?", "Pay Per Click", "Pay Per Customer", "Public Per Click", "Post Per Click", "0"},
        {"Organic Reach?", "Paid", "Free traffic", "Bot", "Email", "1"},
        {"A/B testing?", "Two versions", "Battery", "CPU", "DB", "0"},
        {"Content Marketing goal?", "Value to attract", "Sell now", "Server load", "Spam", "0"},
        {"Social platform?", "Google Ads", "TikTok", "Bing", "Yahoo", "1"},
        {"ROI?", "Return on Investment", "Rate of Interest", "Run of Internet", "Revenue on Internet", "0"},
        {"Conversion?", "User takes desired action", "User leaves", "Server crashes", "Page loads", "0"},
        {"Bounce Rate?", "Exit after one page", "Click rate", "Buy rate", "Share rate", "0"},
        {"CRM stands for?", "Customer Relationship Mgmt", "Content Review", "Cost Reduction", "Client Resource", "0"},
        {"Email tool?", "Mailchimp", "Photoshop", "Excel", "Zoom", "0"},
        {"Influencer Marketing?", "Partner with popular users", "Hacking", "Spamming", "Popups", "0"},
        {"Keyword in SEO?", "Word users type", "Password", "Secret code", "Ad type", "0"},
        {"Backlinking?", "Other sites link to yours", "Link own pages", "Delete links", "Buy links", "0"},
        {"Brand awareness metric?", "Impressions", "CTR", "Conversion", "CPC", "0"},
        {"Pixel in marketing?", "Tracking code", "Screen res", "Image type", "Color", "0"},
        {"Retargeting?", "Ads to visitors", "New audience", "Delete ads", "Change website", "0"},
        {"Copywriting?", "Writing marketing text", "Copying code", "Writing novel", "Translating", "0"},
        {"Short video platform?", "TikTok", "LinkedIn", "Pinterest", "Reddit", "0"},
        {"UGC?", "User Generated Content", "Universal Graphic", "Unified Global", "User Group", "0"},
        {"Sales Funnel?", "Customer journey", "Physical pipe", "Ad type", "Email server", "0"},
        {"B2C?", "Business to Consumer", "Back to Content", "Business to Company", "Brand to Customer", "0"},
        {"Web analytics tool?", "Google Analytics", "Photoshop", "Premiere", "Visual Studio", "0"},
        {"Affiliate Marketing?", "Earn commission", "Sell own", "Hack websites", "Create ads", "0"},
        {"Best post time?", "Depends on audience", "Always 9 AM", "Midnight", "Never", "0"},
        {"Inbound Marketing?", "Attract via content", "Cold calling", "Billboards", "Direct mail", "0"}
    };
    static final String[][] gdBank = {
        {"RGB?", "Red Green Blue", "Red Grey Black", "Rapid Graphic", "Real Graphic", "0"},
        {"CMYK?", "Cyan Magenta Yellow Key", "Color Magic Yellow", "Cyan Magnetic", "Creative Maker", "0"},
        {"Transparency format?", "JPEG", "PNG", "GIF", "BMP", "1"},
        {"Kerning?", "Spacing chars", "Spacing lines", "Font", "Software", "0"},
        {"Leading?", "Spacing lines", "Spacing chars", "Images", "Crop", "0"},
        {"Vector software?", "Photoshop", "Illustrator", "Premiere", "After Effects", "1"},
        {"Raster image?", "Pixels", "Paths", "Infinite res", "3D", "0"},
        {"Vector image?", "Pixels", "Paths", "Low res", "Color", "1"},
        {"Rule of thirds?", "Composition", "Palette", "Font", "Gradient", "0"},
        {"Mockup?", "Realistic rep", "Joke", "Font", "Hardware", "0"},
        {"Print color mode?", "RGB", "CMYK", "HSL", "HEX", "1"},
        {"Screen color mode?", "RGB", "CMYK", "Pantone", "Raster", "0"},
        {"Negative Space?", "Empty space", "Bad colors", "Broken layout", "Dark mode", "0"},
        {"Hierarchy?", "Guide viewer eye", "Font type", "Feature", "Color code", "0"},
        {"Opacity?", "Transparency level", "Brightness", "Contrast", "Font size", "0"},
        {"Gradient?", "Transition between colors", "Solid color", "Pattern", "Filter", "0"},
        {"Best for logos?", "JPEG", "PNG", "SVG", "TIFF", "2"},
        {"UI stands for?", "User Interface", "Universal Interface", "User Info", "Unified Integration", "0"},
        {"UX stands for?", "User Experience", "Universal Exchange", "User Extension", "Unified Experience", "0"},
        {"Contrast?", "Diff light/dark", "Font type", "Color mode", "Software", "0"},
        {"Grid System?", "Aligning elements", "Adding colors", "Editing photos", "Exporting", "0"},
        {"Drop Shadow?", "Visual effect behind", "Font type", "Filter", "Brush", "0"},
        {"Warm color?", "Blue", "Green", "Red", "Purple", "2"},
        {"Cool color?", "Red", "Orange", "Yellow", "Blue", "3"},
        {"Pixel Art?", "Digital art at pixel level", "Pen drawing", "3D modeling", "Photography", "0"},
        {"DPI?", "Dots Per Inch", "Digital Picture Interface", "Data Per Image", "Direct Pixel Injection", "0"},
        {"Remove background tool?", "Magic Wand", "Brush", "Text Tool", "Gradient Tool", "0"},
        {"Typography?", "Art of arranging text", "Art of drawing", "Art of photos", "Art of coding", "0"},
        {"Serif font?", "Font with small lines", "Font without lines", "Cursive", "Bold", "0"},
        {"Sans-Serif?", "Font with lines", "Font without lines", "Handwritten", "3D", "1"}
    };
    static final String[][] csBank = {
        {"C# runs on?", "JVM", "CLR", "Python VM", "V8", "1"},
        {"Inherit class?", "extends", "implements", "inherits", ":", "3"},
        {"Entry point?", "main()", "Main()", "start()", "init()", "1"},
        {"Value type?", "string", "class", "struct", "interface", "2"},
        {"LINQ stands for?", "Language Integrated Query", "Linear Query", "Link Query", "Logical Query", "0"},
        {"Declare var?", "var x = 5;", "let x = 5;", "int x = 5;", "Both A and C", "3"},
        {"Private only?", "public", "private", "protected", "internal", "1"},
        {"using statement?", "Include namespace", "Manage resources", "Create loops", "Define classes", "1"},
        {"Nullable?", "int?", "null int", "int_null", "nullable int", "0"},
        {"Finalizer?", "~ClassName()", "Finalize()", "Dispose()", "delete()", "0"},
        {"Created C#?", "Google", "Microsoft", "Apple", "Oracle", "1"},
        {"Interface keyword?", "class", "interface", "struct", "enum", "1"},
        {"Abstract class?", "abstract", "virtual", "sealed", "override", "0"},
        {"Virtual method?", "abstract", "virtual", "sealed", "override", "1"},
        {"Override method?", "abstract", "virtual", "sealed", "override", "3"},
        {"Try-catch for?", "Exception handling", "Loops", "Variables", "Classes", "0"},
        {"string is?", "Value type", "Reference type", "Struct", "Enum", "1"},
        {"int is?", "Value type", "Reference type", "Class", "Interface", "0"},
        {"const vs readonly?", "const compile-time, readonly runtime", "Same", "const runtime", "readonly compile", "0"},
        {"async/await for?", "Asynchronous programming", "Synchronous", "Loops", "Variables", "0"},
        {"LINQ extension?", ".Where()", ".Filter()", ".Find()", ".Get()", "0"},
        {"Namespace purpose?", "Organize code", "Speed up", "Encrypt", "Delete", "0"},
        {"Garbage collection?", "Automatic memory mgmt", "Manual memory", "Loop", "Class", "0"},
        {"List<T> is?", "Generic collection", "Primitive type", "Loop", "Interface", "0"},
        {"Dictionary for?", "Key-value pairs", "Simple list", "Loops", "Classes", "0"},
        {"Delegate is?", "Function pointer", "Variable", "Class", "Loop", "0"},
        {"Event is?", "Publisher-subscriber", "Loop type", "Variable", "Class", "0"},
        {"Reflection is?", "Runtime type inspection", "Compile-time check", "Loop", "Variable", "0"},
        {"Serialization?", "Object to data", "Data to object", "Loop", "Variable", "0"},
        {"IEnumerable?", "Iteration interface", "Class", "Struct", "Loop", "0"}
    };
    static final String[][] ftBank = {
        {"Pip?", "Currency", "Smallest move", "Platform", "Fee", "1"},
        {"Going Long?", "Selling", "Buying expecting rise", "Holding cash", "Shorting", "1"},
        {"Stop Loss?", "Guarantee profit", "Limit losses", "Increase leverage", "Pay taxes", "1"},
        {"Bull Market?", "Falling", "Rising", "No volume", "Cattle", "1"},
        {"Leverage?", "Borrowed funds", "Crypto", "Indicator", "Strategy", "0"},
        {"Candlestick?", "Price movement", "Volume only", "Rates", "Order", "0"},
        {"Cryptocurrency?", "Apple Stock", "Bitcoin", "USD", "Gold", "1"},
        {"FOMO?", "Fear Of Missing Out", "First Order", "Forex Open", "Federal", "0"},
        {"Take Profit?", "Close at profit", "Close at loss", "Commission", "Wallet", "0"},
        {"DCA?", "Dollar Cost Averaging", "Direct Crypto", "Daily Chart", "Digital Currency", "0"},
        {"Bear Market?", "Falling", "Rising", "No volume", "Bears", "0"},
        {"Going Short?", "Selling expecting fall", "Buying expecting rise", "Holding cash", "Longing", "0"},
        {"Spread?", "Bid-ask difference", "Commission", "Profit", "Stop loss", "0"},
        {"Volatility?", "Price fluctuation", "Volume", "Fixed price", "Commission", "0"},
        {"Support Level?", "Price floor", "Price ceiling", "Trend", "Order", "0"},
        {"Resistance Level?", "Price ceiling", "Price floor", "Trend", "Order", "1"},
        {"Moving Average?", "Trend indicator", "Volume", "Order", "Fee", "0"},
        {"RSI stands for?", "Relative Strength Index", "Real Stock", "Risk Spread", "Rapid Stock", "0"},
        {"MACD stands for?", "Moving Avg Convergence Divergence", "Multiple Asset Crypto Derivative", "Market Avg Cost Data", "Margin Adjustment Curve", "0"},
        {"Order types?", "Market, limit, stop", "Buy, sell, hold", "Open, close", "Long, short", "0"},
        {"Margin Call?", "Broker demands funds", "Profit achieved", "Trade closed", "Bonus", "0"},
        {"Position Sizing?", "How much to trade", "Where", "When", "Why", "0"},
        {"Risk-Reward?", "Profit vs loss ratio", "Win vs loss trades", "Buy vs sell", "Open vs close", "0"},
        {"Whale in crypto?", "Large holder", "Small trader", "Fish", "Exchange", "0"},
        {"HODL?", "Hold long term", "Sell immediately", "Buy high", "Buy low", "0"},
        {"Altcoin?", "Non-Bitcoin crypto", "Alternative stock", "Banking", "Gold", "0"},
        {"Blockchain?", "Distributed ledger", "Centralized DB", "Bank", "Broker", "0"},
        {"Wallet?", "Crypto storage", "Bank account", "Credit card", "Debit card", "0"},
        {"Paper Trading?", "Practice without money", "Trading paper", "Real trading", "Bond trading", "0"},
        {"Trading Journal?", "Record of trades", "Book of tips", "App", "Broker log", "0"}
    };

    static List<QuizQuestion> generateHardQuiz(Course c) {
        List<QuizQuestion> qs = new ArrayList<>(); Random rand = new Random(System.currentTimeMillis());
        String[][] bank;
        if (c.id == 1) bank = citBank;
        else if (c.id == 2) bank = peBank;
        else if (c.id == 3) bank = dmBank;
        else if (c.id == 4) bank = gdBank;
        else if (c.id == 5) bank = csBank;
        else bank = ftBank;

        List<String[]> pool = new ArrayList<>(Arrays.asList(bank));
        Collections.shuffle(pool, rand);
        int limit = Math.min(30, pool.size());
        for (int i = 0; i < limit; i++) {
            String[] data = pool.get(i);
            QuizQuestion q = new QuizQuestion(); q.id = state.nextQuizId++; q.question = data[0];
            q.options = Arrays.asList(data[1], data[2], data[3], data[4]); q.correctIndex = Integer.parseInt(data[5]);
            int correctAnswerTextIdx = q.correctIndex; List<String> originalOpts = new ArrayList<>(q.options);
            Collections.shuffle(q.options, rand); q.correctIndex = q.options.indexOf(originalOpts.get(correctAnswerTextIdx));
            qs.add(q);
        }
        return qs;
    }

    static void doSubmitQuiz(HttpExchange ex, AuthCtx auth, Map<String, String> f) throws IOException {
        long cid = Long.parseLong(f.get("courseId")); Course c; synchronized (LOCK) { c = state.courses.stream().filter(x -> x.id == cid).findFirst().orElse(null); }
        if (c == null) { redirect(ex, "/"); return; }
        List<QuizQuestion> activeQuiz = activeQuizzes.get(auth.user.id);
        if (activeQuiz == null) { redirect(ex, "/quiz/start?id=" + cid); return; }
        int score = 0;
        for (int i = 0; i < activeQuiz.size(); i++) { if (String.valueOf(activeQuiz.get(i).correctIndex).equals(f.get("q" + i))) score++; }
        activeQuizzes.remove(auth.user.id);
        int total = activeQuiz.size();
        int finalPct = total == 0 ? 0 : (score * 100) / total;
        boolean passed = finalPct >= 70;
        Enrollment e; synchronized (LOCK) {
            e = state.enrollments.stream().filter(x -> x.userId == auth.user.id && x.courseId == cid).findFirst().orElse(null);
            if (e != null) {
                if (passed) { e.bestQuizScore = Math.max(e.bestQuizScore, finalPct); e.certificateIssued = true; e.lockoutUntil = 0; }
                else { e.lockoutUntil = System.currentTimeMillis() + (24 * 60 * 60 * 1000L); }
                saveState();
            }
        }
        String msg = passed ? "Congratulations! You passed! Your certificate is ready." : "You need 70% to pass. Locked for 24 hours.";
        String color = passed ? "text-emerald-400" : "text-pink-400";
        String confetti = passed ? "<script>setTimeout(function(){var c=document.getElementById('confetti');if(c)c.style.display='block';confetti({particleCount:200,spread:90,origin:{y:0.5},colors:['#10b981','#22d3ee','#f472b6','#ffffff']});},400);</script>" : "";
        String b = "<div class='animate-fade-in card-neon max-w-md mx-auto mt-20 p-8 rounded-[2rem] text-center shadow-[0_0_40px_rgba(16,185,129,0.3)]'>"
            + "<h1 class='text-3xl font-bold mb-4 text-cyan-400'>Quiz Result</h1>"
            + "<div class='text-6xl font-bold mb-6 " + color + "'>" + finalPct + "%</div>"
            + "<p class='text-cyan-200 mb-8'>" + msg + "</p>"
            + "<a href='/dashboard' class='btn-neon inline-block px-8 py-3 rounded-xl font-medium'>Go to Dashboard</a></div>"
            + confetti;
        sendHtml(ex, 200, page("Quiz Result", b, auth));
    }

    static void doAddCourse(HttpExchange ex, AuthCtx auth, Map<String, String> f) throws IOException {
        synchronized (LOCK) { Course c = new Course(); c.id = state.nextCourseId++; c.title = f.getOrDefault("title", ""); c.instructor = f.getOrDefault("instructor", ""); c.description = f.getOrDefault("description", ""); c.imageUrl = f.getOrDefault("imageUrl", ""); state.courses.add(c); saveState(); }
        redirect(ex, "/admin?msg=CourseCreated");
    }

    static void doDeleteCourse(HttpExchange ex, AuthCtx auth, Map<String, String> f) throws IOException {
        long cid = Long.parseLong(f.get("courseId"));
        synchronized (LOCK) { state.courses.removeIf(c -> c.id == cid); state.enrollments.removeIf(e -> e.courseId == cid); saveState(); }
        redirect(ex, "/admin?msg=CourseDeleted");
    }

    static void doAddLesson(HttpExchange ex, AuthCtx auth, Map<String, String> f) throws IOException {
        long cid = Long.parseLong(f.get("courseId"));
        synchronized (LOCK) {
            Course c = state.courses.stream().filter(x -> x.id == cid).findFirst().orElse(null);
            if (c != null) {
                List<String> vids = new ArrayList<>();
                for (String part : f.getOrDefault("videoUrlEn", "").split("[\\s,;]+")) {
                    String id = videoId(part);
                    if (!id.isEmpty() && !vids.contains(id)) vids.add(id);
                }
                c.lessons.add(lesson(f.getOrDefault("title", ""), f.getOrDefault("content", ""), vids.toArray(new String[0])));
                saveState();
            }
        }
        redirect(ex, "/admin/course?id=" + cid + "&msg=LessonAdded");
    }

    static void doDeleteLesson(HttpExchange ex, AuthCtx auth, Map<String, String> f) throws IOException {
        long cid = Long.parseLong(f.get("courseId")), lid = Long.parseLong(f.get("lessonId"));
        synchronized (LOCK) {
            Course c = state.courses.stream().filter(x -> x.id == cid).findFirst().orElse(null);
            if (c != null) { c.lessons.removeIf(l -> l.id == lid); for(Enrollment e : state.enrollments) if(e.courseId == cid) e.completedLessonIds.remove(lid); saveState(); }
        }
        redirect(ex, "/admin/course?id=" + cid + "&msg=LessonDeleted");
    }

    static void renderAdminSettings(HttpExchange ex, AuthCtx auth) throws IOException {
        String msg = queryParam(ex, "msg"); String alert = "";
        if ("InvalidCurrentPassword".equals(msg)) alert = "<div class='animate-fade-in bg-pink-500/20 border border-pink-400/30 text-pink-300 px-4 py-3 rounded-xl mb-6'>Current password is incorrect.</div>";
        String b = "<div class='animate-slide-up card-neon max-w-2xl mx-auto p-8 rounded-[2rem] mt-10 shadow-[0_0_30px_rgba(16,185,129,0.2)]'>"
            + "<h1 class='text-xl font-bold text-cyan-400 mb-6'>Admin Settings</h1>" + alert
            + "<form method='post' action='/admin/settings' class='space-y-5'>"
            + "<input type='hidden' name='_csrf' value='" + auth.csrf() + "'>"
            + "<div><label class='block text-sm font-medium text-cyan-400 mb-1'>Current Password</label><input name='currentPassword' type='password' required class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-xl text-cyan-100 focus:ring-2 focus:ring-cyan-500/50'></div>"
            + "<div><label class='block text-sm font-medium text-cyan-400 mb-1'>New Email</label><input name='newEmail' type='email' class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-xl text-cyan-100 focus:ring-2 focus:ring-cyan-500/50'></div>"
            + "<div><label class='block text-sm font-medium text-cyan-400 mb-1'>New Password (min 6)</label><input name='newPassword' type='password' minlength='6' class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-xl text-cyan-100 focus:ring-2 focus:ring-cyan-500/50'></div>"
            + "<button class='btn-neon w-full py-3 rounded-xl font-medium shadow-lg'>Update Credentials</button></form></div>";
        sendHtml(ex, 200, page("Admin Settings", b, auth));
    }

    static void renderAdminDashboard(HttpExchange ex, AuthCtx auth) throws IOException {
        String msg = queryParam(ex, "msg"); List<Course> cs; synchronized (LOCK) { cs = new ArrayList<>(state.courses); }
        StringBuilder rows = new StringBuilder();
        for (Course c : cs) {
            rows.append("<tr class='border-b border-cyan-500/20 hover:bg-cyan-500/5 transition group'>")
                .append("<td class='py-3 px-4 text-cyan-200 font-medium text-sm'>").append(esc(c.title)).append("</td>")
                .append("<td class='py-3 px-4 text-cyan-300 text-sm'>").append(esc(c.instructor)).append("</td>")
                .append("<td class='py-3 px-4 text-cyan-300 text-sm'>").append(c.lessons.size()).append("</td>")
                .append("<td class='py-3 px-4 flex gap-3'>")
                .append("<a href='/admin/course?id=").append(c.id).append("' class='text-cyan-400 font-medium text-sm hover:text-cyan-200 transition'>Manage</a>")
                .append("<form method='post' action='/admin/delete-course' onsubmit=\"return confirm('Delete this course?');\">")
                .append("<input type='hidden' name='courseId' value='").append(c.id).append("'><input type='hidden' name='_csrf' value='").append(auth.csrf()).append("'>")
                .append("<button type='submit' class='text-pink-400 font-medium text-sm hover:text-pink-200 transition underline'>Delete</button></form></td></tr>");
        }
        String successDiv = (msg != null) ? "<div class='animate-fade-in bg-emerald-500/20 border border-emerald-500/30 text-emerald-300 px-4 py-3 rounded-xl mb-6'>Success: " + esc(msg) + "</div>" : "";
        String body = successDiv
            + "<div class='flex flex-col md:flex-row justify-between items-start md:items-center mb-10 gap-4 animate-fade-in'>"
            + "<div><h1 class='text-2xl font-extrabold text-cyan-300 mb-2'>Admin Dashboard</h1><p class='text-cyan-400 text-sm'>Manage your institute</p></div>"
            + "<div class='flex flex-wrap gap-2'>"
            + "<a href='/admin/settings' class='bg-black/40 border border-cyan-500/30 text-cyan-300 px-4 py-2 rounded-xl font-medium text-sm hover:bg-cyan-500/10 transition'>Settings</a>"
            + "<a href='/admin/students' class='bg-black/40 border border-cyan-500/30 text-cyan-300 px-4 py-2 rounded-xl font-medium text-sm hover:bg-cyan-500/10 transition'>Students</a>"
            + "<a href='/admin/add-course' class='btn-neon px-4 py-2 rounded-xl font-medium text-sm'>+ Course</a></div></div>"
            + "<div class='card-neon rounded-[2rem] overflow-x-auto animate-slide-up'>"
            + "<table class='w-full text-left border-collapse min-w-[600px]'><thead><tr class='bg-black/30 text-cyan-400 text-xs uppercase'>"
            + "<th class='py-3 px-4'>Course</th><th class='py-3 px-4'>Instructor</th><th class='py-3 px-4'>Lessons</th><th class='py-3 px-4'>Actions</th></tr></thead>"
            + "<tbody>" + rows.toString() + "</tbody></table></div>";
        sendHtml(ex, 200, page("Admin Dashboard", body, auth));
    }

    static void renderAdminStudents(HttpExchange ex, AuthCtx auth) throws IOException {
        String msg = queryParam(ex, "msg"); List<User> studs; synchronized (LOCK) { studs = state.users.stream().filter(u -> "STUDENT".equals(u.role)).collect(Collectors.toList()); }
        StringBuilder rows = new StringBuilder();
        for (User u : studs) {
            List<Enrollment> uEnrolls; synchronized (LOCK) { uEnrolls = state.enrollments.stream().filter(e -> e.userId == u.id).collect(Collectors.toList()); }
            StringBuilder courseList = new StringBuilder();
            for (Enrollment e : uEnrolls) {
                Course c; synchronized(LOCK) { c = state.courses.stream().filter(x -> x.id == e.courseId).findFirst().orElse(null); }
                if (c != null) {
                    courseList.append("<div class='flex justify-between items-center bg-black/30 border border-cyan-500/20 px-3 py-2 rounded-xl mt-2 group hover:bg-cyan-500/10 transition'>")
                        .append("<span class='text-xs text-cyan-300 truncate pr-2'>").append(esc(c.title)).append("</span>")
                        .append("<form method='post' action='/admin/kick-student'><input type='hidden' name='userId' value='").append(u.id).append("'><input type='hidden' name='courseId' value='").append(c.id).append("'><input type='hidden' name='_csrf' value='").append(auth.csrf()).append("'><button class='text-xs text-pink-400 font-bold hover:underline'>Kick</button></form></div>");
                }
            }
            rows.append("<tr class='border-b border-cyan-500/20 hover:bg-cyan-500/5 transition'>")
                .append("<td class='py-3 px-4 text-cyan-200 font-medium text-sm'>").append(esc(u.fullName))
                .append("<span class='text-xs text-cyan-400 block'>").append(esc(u.email)).append("</span></td>")
                .append("<td class='py-3 px-4'><span class='font-mono text-amber-400 bg-amber-900/20 px-2 py-1 rounded border border-amber-500/30 text-xs'>••••••••</span></td>")
                .append("<td class='py-3 px-4'>").append(courseList.length()==0 ? "<span class='text-cyan-400 text-xs'>None</span>" : courseList.toString()).append("</td></tr>");
        }
        String msgDiv = (msg != null) ? "<div class='animate-fade-in bg-emerald-500/20 text-emerald-300 px-4 py-2 rounded-xl mb-4 text-sm'>Student removed.</div>" : "";
        String body = msgDiv
            + "<div class='mb-6 flex justify-between animate-fade-in'><h1 class='text-2xl font-extrabold text-cyan-300'>Students</h1>"
            + "<a href='/admin' class='text-cyan-400 text-sm hover:underline'>← Back</a></div>"
            + "<div class='card-neon rounded-[2rem] overflow-x-auto animate-slide-up'>"
            + "<table class='w-full text-left min-w-[600px]'><thead><tr class='bg-black/30 text-cyan-400 text-xs'>"
            + "<th class='py-3 px-4'>Student</th><th class='py-3 px-4'>Password</th><th class='py-3 px-4'>Courses</th></tr></thead>"
            + "<tbody>" + rows.toString() + "</tbody></table></div>";
        sendHtml(ex, 200, page("Students", body, auth));
    }

    static void renderCatalog(HttpExchange ex, AuthCtx auth) throws IOException {
        String search = queryParam(ex, "search"); List<Course> cs;
        synchronized (LOCK) { cs = state.courses.stream().filter(c -> search == null || search.isEmpty() || c.title.toLowerCase().contains(search.toLowerCase())).collect(Collectors.toList()); }
        StringBuilder cards = new StringBuilder();
        int delay = 0;
        for (Course c : cs) {
            String img = (c.imageUrl != null && !c.imageUrl.isEmpty()) ? c.imageUrl : "/assets/bg-dashboard.jpg";
            cards.append("<div class='card-neon rounded-[2rem] overflow-hidden flex flex-col group animate-slide-up' style='animation-delay:" + (delay*90) + "ms'>")
                .append("<div class='h-56 bg-cover bg-center flex items-center justify-center p-4 relative group-hover:scale-110 transition-transform duration-700 ease-out' style='background-image: url(\"" + esc(img) + "\")'>")
                .append("<div class='absolute inset-0 bg-gradient-to-t from-black/80 via-black/20 to-transparent'></div>")
                .append("<div class='relative z-10 text-xl font-extrabold text-cyan-200 text-center drop-shadow-lg px-4'>").append(esc(c.title)).append("</div>")
                .append("<div class='absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity duration-300'>")
                .append("<button class='btn-neon px-8 py-3 rounded-xl text-sm font-bold shadow-lg hover:scale-105 transition'>View Lessons</button>")
                .append("</div></div>")
                .append("<div class='p-6 flex-grow flex flex-col'><p class='text-sm text-cyan-300 mb-6 flex-grow line-clamp-3'>").append(esc(c.description)).append("</p>")
                .append("<div class='flex items-center justify-between mt-auto'><span class='text-xs text-cyan-400 font-bold tracking-wide'>").append(esc(c.instructor)).append("</span>")
                .append("<a href='/course?id=").append(c.id).append("' class='btn-neon text-white px-4 py-2 rounded-xl text-xs font-medium hover:scale-105 transition'>View Course</a></div></div></div>");
            delay++;
        }
        String searchVal = (search != null) ? esc(search) : "";
        String grid = (cards.length() == 0) ? "<div class='text-center py-24 text-cyan-400 animate-fade-in'>No courses found.</div>" : "<div class='grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6'>" + cards + "</div>";
        String body = "<div class='flex flex-col md:flex-row justify-between items-center mb-12 gap-4 animate-fade-in'>"
            + "<div><h1 class='text-3xl font-extrabold text-cyan-300 tracking-tight'>Explore Courses</h1><p class='text-cyan-400 text-sm mt-1'>Choose your path to excellence</p></div>"
            + "<form method='get' action='/catalog' class='w-full md:w-80 relative'>"
            + "<input type='text' name='search' value='" + searchVal + "' placeholder='Search courses...' class='w-full pl-10 pr-4 py-3 bg-black/40 border border-cyan-500/30 rounded-2xl text-cyan-100 text-sm focus:ring-2 focus:ring-cyan-500/50 outline-none transition'>"
            + "<svg class='w-5 h-5 text-cyan-500 absolute left-3 top-1/2 -mt-2.5' fill='none' stroke='currentColor' viewBox='0 0 24 24'><path stroke-linecap='round' stroke-linejoin='round' stroke-width='2' d='M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z'></path></svg></form></div>" + grid;
        sendHtml(ex, 200, page("Catalog", body, auth));
    }

    static void renderStudentDashboard(HttpExchange ex, AuthCtx auth) throws IOException {
        List<Enrollment> mine; synchronized (LOCK) { mine = state.enrollments.stream().filter(e -> e.userId == auth.user.id).collect(Collectors.toList()); }
        StringBuilder cards = new StringBuilder();
        int i = 0;
        for (Enrollment e : mine) {
            Course c; synchronized (LOCK) { c = state.courses.stream().filter(x -> x.id == e.courseId).findFirst().orElse(null); }
            if (c != null) {
                int total = c.lessons.size(), done = e.completedLessonIds.size(), pct = total == 0 ? 0 : (done * 100 / total);
                String actionBtn = e.certificateIssued
                    ? "<a href='/certificate?id=" + c.id + "' class='block text-center bg-gradient-to-r from-emerald-500 to-emerald-700 hover:from-emerald-400 hover:to-emerald-600 text-white font-medium py-3 rounded-2xl mt-3 text-sm shadow-[0_0_20px_rgba(16,185,129,0.4)] hover:shadow-[0_0_30px_rgba(16,185,129,0.6)] transition'>🏆 View Certificate</a>"
                    : "<a href='/course?id=" + c.id + "' class='block text-center bg-gradient-to-r from-cyan-500 to-cyan-700 hover:from-cyan-400 hover:to-cyan-600 text-white font-medium py-3 rounded-2xl mt-3 text-sm shadow-[0_0_20px_rgba(6,182,212,0.4)] hover:shadow-[0_0_30px_rgba(6,182,212,0.6)] transition'>Continue Learning</a>";
                String certPreview = "";
                if (!e.certificateIssued) {
                    certPreview = "<div class='mt-4 relative overflow-hidden rounded-xl border border-cyan-500/20 bg-black/30'>"
                        + "<div class='absolute inset-0 bg-black/60 z-10 flex flex-col items-center justify-center p-6'>"
                        + "<svg class='w-10 h-10 text-cyan-400 mb-2' fill='none' stroke='currentColor' viewBox='0 0 24 24'><path stroke-linecap='round' stroke-linejoin='round' stroke-width='2' d='M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z'></path></svg>"
                        + "<span class='text-xs font-bold text-cyan-400 uppercase tracking-wider'>Locked</span></div>"
                        + "<div class='w-full h-40 bg-white/5 flex flex-col items-center justify-center grayscale opacity-50'>"
                        + "<div class='text-cyan-900 font-bold text-xs uppercase tracking-widest'>Ghouri Institute</div>"
                        + "<div class='text-cyan-800 text-lg font-serif font-bold mt-1'>CERTIFICATE</div>"
                        + "<div class='text-cyan-700 text-xs mt-2 font-bold'>" + esc(auth.user.fullName) + "</div></div></div>";
                }
                cards.append("<div class='card-neon rounded-[2rem] p-6 flex flex-col group hover:border-cyan-500/50 transition animate-slide-up' style='animation-delay:" + (i*120) + "ms'>");
                cards.append("<div class='flex justify-between items-start mb-4'><h3 class='text-lg font-bold text-cyan-200 group-hover:text-cyan-400 transition'>").append(esc(c.title)).append("</h3><span class='text-xs bg-cyan-900/20 text-cyan-400 px-2 py-1 rounded border border-cyan-500/30'>" + total + " Lessons</span></div>");
                cards.append("<div class='text-xs text-cyan-400 mb-3'>Progress: ").append(done).append(" / ").append(total).append("</div>");
                cards.append("<div class='w-full bg-cyan-900/30 rounded-full h-2 mb-4 overflow-hidden'><div class='bg-gradient-to-r from-cyan-400 to-emerald-400 h-2 rounded-full transition-all duration-700' style='width: ").append(pct).append("%;'></div></div>");
                cards.append(actionBtn).append(" ").append(certPreview).append("</div>");
                i++;
            }
        }
        String empty = (cards.length() == 0) ? "<div class='text-center py-24 card-neon rounded-[2rem] animate-fade-in'><div class='w-16 h-16 bg-cyan-900/20 rounded-full flex items-center justify-center mx-auto mb-4 text-cyan-400 text-3xl'>📚</div><h3 class='text-xl font-bold text-cyan-300 mb-4'>No courses yet</h3><p class='text-cyan-400 mb-6'>Start your learning journey today</p><a href='/catalog' class='btn-neon px-8 py-3 rounded-xl text-sm'>Browse Catalog</a></div>" : "<div class='grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6'>" + cards + "</div>";
        String body = "<div class='relative overflow-hidden rounded-[2rem] mb-10 card-neon animate-fade-in'>"
            + "<div class='glass-photo' style=\"background-image:url('/assets/bg-dashboard.jpg');\"></div>"
            + "<div class='glass-veil'></div>"
            + "<div class='relative z-10 p-8 md:p-12 flex flex-col md:flex-row justify-between items-start md:items-end gap-6'>"
            + "<div><div class='text-[10px] md:text-[11px] uppercase tracking-[0.28em] text-cyan-400 mb-3 font-bold'>Ghouri Institute · Learning Portal</div>"
            + "<h1 class='text-3xl md:text-4xl font-extrabold text-white tracking-tight mb-2'>My Learning</h1>"
            + "<p class='text-cyan-200/80 text-sm'>Welcome back, <span class='text-cyan-300 font-semibold'>" + esc(auth.user.fullName) + "</span></p></div>"
            + "<a href='/catalog' class='btn-neon px-6 py-3 rounded-xl text-sm font-bold whitespace-nowrap'>+ Browse Catalog</a></div></div>" + empty;
        sendHtml(ex, 200, page("My Learning", body, auth));
    }

    static void renderCourseForm(HttpExchange ex, AuthCtx auth) throws IOException {
        String b = "<div class='animate-slide-up card-neon max-w-2xl mx-auto p-8 rounded-[2rem] mt-10 shadow-[0_0_30px_rgba(16,185,129,0.2)]'>"
            + "<h1 class='text-xl font-bold text-cyan-400 mb-6'>Create Course</h1>"
            + "<form method='post' action='/admin/add-course' class='space-y-5'>"
            + "<input type='hidden' name='_csrf' value='" + auth.csrf() + "'>"
            + "<input name='title' placeholder='Course Title' required class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-xl text-cyan-100 focus:ring-2 focus:ring-cyan-500/50 text-sm transition'>"
            + "<input name='instructor' placeholder='Instructor Name' required class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-xl text-cyan-100 focus:ring-2 focus:ring-cyan-500/50 text-sm transition'>"
            + "<input name='imageUrl' placeholder='Cover image URL ya /assets/course-xxx.jpg' class='w-full px-4 py-3' bg-black/40 border border-cyan-500/30 rounded-xl text-cyan-100 focus:ring-2 focus:ring-cyan-500/50 text-sm transition'>"
            + "<textarea name='description' placeholder='Course Description' required class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-xl text-cyan-100 h-32 text-sm focus:ring-2 focus:ring-cyan-500/50 transition'></textarea>"
            + "<button class='btn-neon w-full py-3 rounded-xl font-medium text-sm shadow-lg'>Create Course</button></form></div>";
        sendHtml(ex, 200, page("Add Course", b, auth));
    }

    static void renderCoursePlayer(HttpExchange ex, AuthCtx auth) throws IOException {
        long id; try { id = Long.parseLong(queryParam(ex, "id")); } catch (Exception e) { redirect(ex, "/"); return; }
        Course c; synchronized (LOCK) { c = state.courses.stream().filter(x -> x.id == id).findFirst().orElse(null); }
        if (c == null) { redirect(ex, "/"); return; }
        Enrollment e = auth.loggedIn() ? state.enrollments.stream().filter(x -> x.userId == auth.user.id && x.courseId == id).findFirst().orElse(null) : null;
        StringBuilder ls = new StringBuilder();
        int lessonIdx = 0;
        for (Lesson l : c.lessons) {
            boolean done = (e != null && e.completedLessonIds.contains(l.id));
            String action = (e == null) ? "" : (done ? "<span class='text-emerald-400 text-xs font-bold flex items-center gap-1 animate-pulse'>✓ Completed</span>" :
                "<form method='post' action='/complete' class='inline'><input type='hidden' name='courseId' value='" + c.id + "'><input type='hidden' name='lessonId' value='" + l.id + "'><input type='hidden' name='_csrf' value='" + auth.csrf() + "'><button class='btn-neon text-xs px-4 py-2 rounded-xl font-medium hover:scale-105 transition'>Mark Complete</button></form>");
            // Video player: multiple verified sources + automatic backup switching
            String media = videoPlayerHtml(l, c);
            ls.append("<div class='card-neon p-6 md:p-8 rounded-[2rem] mb-6 group animate-slide-up' style='animation-delay:" + (lessonIdx*150) + "ms'>")
                .append("<div class='flex flex-col md:flex-row justify-between items-start md:items-center gap-4 mb-6'>")
                .append("<div><h3 class='text-xl font-bold text-cyan-200 group-hover:text-cyan-400 transition'>").append(esc(l.title)).append("</h3><p class='text-xs text-cyan-500 mt-1'>Lesson ").append(lessonIdx+1).append(" • ").append(c.title).append("</p></div>")
                .append("<div>").append(action).append("</div></div>")
                .append("<p class='text-cyan-300 text-sm mb-6 leading-relaxed'>").append(esc(l.content)).append("</p>").append(media).append("</div>");
            lessonIdx++;
        }
        String enrollBtn = (e == null) ? "<a href='/enroll?id=" + c.id + "' class='btn-neon px-8 py-3 rounded-xl font-bold text-center text-sm shadow-lg'>Enroll Now</a>" : "";
        String quizBtn = (e != null) ? "<a href='/quiz/instructions?id=" + c.id + "' class='bg-gradient-to-r from-pink-500 to-pink-700 hover:from-pink-400 hover:to-pink-600 text-white px-8 py-3 rounded-xl font-bold text-sm flex items-center justify-center gap-3 shadow-[0_0_20px_rgba(236,72,153,0.4)] hover:shadow-[0_0_30px_rgba(236,72,153,0.6)] transition'>🎯 Take Quiz &amp; Get Certificate</a>" : "";
        String body = "<a href='/dashboard' class='inline-flex items-center gap-2 text-xs text-cyan-400 hover:text-cyan-200 mb-6 animate-fade-in'>← Back to Dashboard</a>"
            + "<div class='relative overflow-hidden card-neon rounded-[2rem] mb-8 animate-fade-in' style='animation-delay:100ms'>"
            + "<div class='glass-photo' style=\"background-image:url('" + esc(c.imageUrl == null || c.imageUrl.isEmpty() ? "/assets/bg-hero.jpg" : c.imageUrl) + "');\"></div>"
            + "<div class='glass-veil'></div>"
            + "<div class='relative z-10 p-8 md:p-12 flex flex-col md:flex-row justify-between items-start md:items-center gap-6'>"
            + "<div class='flex-shrink-0'><div class='w-16 h-16 bg-gradient-to-br from-cyan-500 to-emerald-500 rounded-[1rem] flex items-center justify-center text-white text-3xl font-bold shadow-[0_0_30px_rgba(6,182,212,0.4)]'>📖</div></div>"
            + "<div><h1 class='text-3xl font-extrabold text-white mb-2 tracking-tight drop-shadow'>" + esc(c.title) + "</h1>"
            + "<p class='text-cyan-100/80 mb-4 text-sm leading-relaxed max-w-2xl'>" + esc(c.description) + "</p>"
            + "<div class='flex items-center gap-3'><div class='w-8 h-8 bg-cyan-900/60 rounded-full flex items-center justify-center text-cyan-300 text-xs font-bold'>👨‍🏫</div><div class='text-xs text-cyan-300 font-medium'>Instructor: " + esc(c.instructor) + "</div></div></div>"
            + "<div class='flex flex-col gap-3 w-full md:w-auto'>" + quizBtn + enrollBtn + "</div></div></div>"
            + "<h2 class='text-2xl font-bold text-cyan-200 mb-6 flex items-center gap-2'><span class='w-8 h-8 bg-cyan-900/50 text-cyan-400 rounded-full flex items-center justify-center text-xs font-bold'>📚</span>Course Content</h2>" + ls.toString()
            + VIDEO_SCRIPT;
        sendHtml(ex, 200, page(c.title, body, auth));
    }

    static void renderAdminCourseDetails(HttpExchange ex, AuthCtx auth) throws IOException {
        String msg = queryParam(ex, "msg"); long id; try { id = Long.parseLong(queryParam(ex, "id")); } catch (Exception e) { redirect(ex, "/admin"); return; }
        Course c; synchronized (LOCK) { c = state.courses.stream().filter(x -> x.id == id).findFirst().orElse(null); }
        if (c == null) { redirect(ex, "/admin"); return; }
        StringBuilder ls = new StringBuilder();
        for (Lesson l : c.lessons) {
            ls.append("<li class='text-cyan-300 py-3 border-b border-cyan-500/20 flex justify-between text-sm group hover:bg-cyan-500/5 transition'>")
                .append("<span class='truncate pr-2'>").append(esc(l.title)).append("</span>")
                .append("<form method='post' action='/admin/delete-lesson' onsubmit=\"return confirm('Remove this lesson?');\" class='inline'>")
                .append("<input type='hidden' name='courseId' value='").append(c.id).append("'><input type='hidden' name='lessonId' value='").append(l.id).append("'><input type='hidden' name='_csrf' value='").append(auth.csrf()).append("'>")
                .append("<button class='text-pink-400 text-xs font-bold hover:text-pink-200 transition underline'>Remove</button></form></li>");
        }
        String successDiv = (msg != null) ? "<div class='animate-fade-in bg-emerald-500/20 text-emerald-300 px-4 py-3 rounded-xl mb-6 text-sm'>Success</div>" : "";
        String body = "<a href='/admin' class='text-xs text-cyan-400 mb-4 inline-block animate-fade-in hover:underline'>← Back</a>" + successDiv
            + "<h1 class='text-xl font-bold text-cyan-300 mb-6'>Manage: " + esc(c.title) + "</h1>"
            + "<div class='max-w-2xl card-neon p-6 md:p-8 rounded-[2rem] animate-slide-up'>"
            + "<h2 class='text-base font-bold text-cyan-400 mb-4'>Add New Lesson</h2>"
            + "<form method='post' action='/admin/add-lesson' class='space-y-4'>"
            + "<input type='hidden' name='courseId' value='" + c.id + "'><input type='hidden' name='_csrf' value='" + auth.csrf() + "'>"
            + "<input name='title' placeholder='Lesson Title' required class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-xl text-cyan-100 focus:ring-2 focus:ring-cyan-500/50 text-sm transition'>"
            + "<textarea name='content' placeholder='Lesson Description' required class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-xl text-cyan-100 h-24 text-sm focus:ring-2 focus:ring-cyan-500/50 transition'></textarea>"
            + "<input name='videoUrlEn' placeholder='YouTube links / IDs (backup ke liye comma se alag karein)' class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-xl text-cyan-100 text-sm focus:ring-2 focus:ring-cyan-500/50 transition'><p class='text-[11px] text-cyan-500/80 -mt-2'>Tip: 2-3 links dein — pehli na chale to system khud backup laga dega.</p>"
            + "<button class='btn-neon w-full py-3 rounded-xl font-medium text-sm shadow-lg'>Add Lesson</button></form>"
            + "<div class='mt-8'><h3 class='text-xs text-cyan-500 mb-3 uppercase tracking-wider'>Current Lessons</h3><ul>" + ls.toString() + "</ul></div></div>";
        sendHtml(ex, 200, page("Manage Course", body, auth));
    }

    static void showQuizInstructions(HttpExchange ex, AuthCtx auth) throws IOException {
        long cid; try { cid = Long.parseLong(queryParam(ex, "id")); } catch (Exception e) { redirect(ex, "/"); return; }
        Course c; synchronized (LOCK) { c = state.courses.stream().filter(x -> x.id == cid).findFirst().orElse(null); }
        Enrollment en = state.enrollments.stream().filter(x -> x.userId == auth.user.id && x.courseId == cid).findFirst().orElse(null);
        if (c == null || en == null) { redirect(ex, "/course?id=" + cid); return; }
        if (en.lockoutUntil > System.currentTimeMillis()) {
            long hrs = (en.lockoutUntil - System.currentTimeMillis()) / (1000 * 60 * 60);
            String msg = "You failed previously. Locked for " + (hrs > 0 ? hrs + " hours" : "less than an hour") + ".";
            String b = "<div class='animate-fade-in card-neon max-w-md mx-auto mt-20 p-8 rounded-[2rem] text-center shadow-[0_0_30px_rgba(236,72,153,0.2)]'><h1 class='text-xl font-bold text-pink-400 mb-2'>Locked</h1><p class='text-cyan-200 mb-6 text-sm'>" + msg + "</p><a href='/course?id=" + cid + "' class='btn-neon inline-block px-8 py-3 rounded-xl text-sm'>Back to Course</a></div>";
            sendHtml(ex, 403, page("Locked", b, auth)); return;
        }
        String b = "<div class='animate-slide-up card-neon max-w-2xl mx-auto mt-10 p-8 md:p-12 rounded-[2rem] shadow-[0_0_30px_rgba(16,185,129,0.2)]'>"
            + "<div class='text-center mb-8'><div class='w-12 h-12 bg-pink-500/20 rounded-full flex items-center justify-center mx-auto mb-4 text-pink-400 text-2xl'>🎯</div><h1 class='text-2xl font-extrabold text-cyan-200 mb-2'>Ready for the Final Test?</h1><p class='text-cyan-400 text-sm'>Test your knowledge and earn your certificate</p></div>"
            + "<div class='bg-black/40 rounded-[1rem] p-6 mb-8 space-y-4 text-sm border border-cyan-500/20'>"
            + "<p class='text-cyan-200 flex items-center gap-3'><span class='w-6 h-6 bg-cyan-500 rounded-full flex items-center justify-center text-xs font-bold text-black'>1</span>30 Unique Questions — no repeats.</p>"
            + "<p class='text-cyan-200 flex items-center gap-3'><span class='w-6 h-6 bg-cyan-500 rounded-full flex items-center justify-center text-xs font-bold text-black'>2</span>25 seconds per question.</p>"
            + "<p class='text-cyan-200 flex items-center gap-3'><span class='w-6 h-6 bg-cyan-500 rounded-full flex items-center justify-center text-xs font-bold text-black'>3</span>No going back once you click Next.</p>"
            + "<p class='text-cyan-200 flex items-center gap-3'><span class='w-6 h-6 bg-cyan-500 rounded-full flex items-center justify-center text-xs font-bold text-black'>4</span>Score 70% or higher to pass and get your certificate.</p></div>"
            + "<div class='flex flex-col sm:flex-row gap-4'>"
            + "<a href='/course?id=" + cid + "' class='w-full sm:w-1/2 text-center bg-black/40 border border-cyan-500/30 hover:bg-cyan-500/10 text-cyan-200 px-6 py-3 rounded-[1rem] text-sm font-medium transition'>Cancel</a>"
            + "<a href='/quiz/start?id=" + cid + "' class='w-full sm:w-1/2 text-center btn-neon px-6 py-3 rounded-[1rem] font-bold text-sm shadow-lg'>Start Test Now</a></div></div>";
        sendHtml(ex, 200, page("Instructions", b, auth));
    }

    static void showQuiz(HttpExchange ex, AuthCtx auth) throws IOException {
        long id; try { id = Long.parseLong(queryParam(ex, "id")); } catch (Exception e) { redirect(ex, "/"); return; }
        Course c; synchronized (LOCK) { c = state.courses.stream().filter(x -> x.id == id).findFirst().orElse(null); }
        if (c == null) { redirect(ex, "/"); return; }
        Enrollment en = state.enrollments.stream().filter(x -> x.userId == auth.user.id && x.courseId == id).findFirst().orElse(null);
        if (en == null) { redirect(ex, "/course?id=" + id); return; }
        if (en.lockoutUntil > System.currentTimeMillis()) { redirect(ex, "/quiz/instructions?id=" + id); return; }
        List<QuizQuestion> qs = generateHardQuiz(c); activeQuizzes.put(auth.user.id, qs);
        StringBuilder qHtml = new StringBuilder();
        for (int i = 0; i < qs.size(); i++) {
            QuizQuestion q = qs.get(i);
            qHtml.append("<div id='question_").append(i).append("' class='question-step hidden animate-fade-in'>");
            qHtml.append("<div class='flex justify-between items-center mb-6'>");
            qHtml.append("<div class='text-xs text-cyan-400 uppercase font-bold tracking-wider'>Question ").append(i+1).append(" of ").append(qs.size()).append("</div>");
            qHtml.append("<div class='flex items-center gap-3 bg-black/40 px-4 py-3 rounded-full border border-cyan-500/20 shadow-inner'>");
            qHtml.append("<svg class='w-5 h-5 text-pink-400' fill='none' stroke='currentColor' viewBox='0 0 24 24'><path stroke-linecap='round' stroke-linejoin='round' stroke-width='2' d='M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z'></path></svg>");
            qHtml.append("<span id='timer_").append(i).append("' class='text-lg font-bold text-cyan-400 font-mono'>25s</span></div></div>");
            qHtml.append("<h3 class='text-xl md:text-2xl font-bold text-cyan-200 mb-6'>").append(esc(q.question)).append("</h3>");
            for (int j = 0; j < q.options.size(); j++) {
                qHtml.append("<label class='flex items-center p-4 rounded-[1rem] hover:bg-cyan-900/20 cursor-pointer mb-3 border border-cyan-500/20 transition-all group hover:border-cyan-400/50 hover:shadow-[0_0_15px_rgba(6,182,212,0.3)]'>");
                qHtml.append("<input type='radio' name='q").append(i).append("' value='").append(j).append("' class='w-5 h-5 mr-4 text-cyan-500 focus:ring-2 focus:ring-cyan-500'>");
                qHtml.append("<span class='text-cyan-200 text-sm font-medium'>").append(esc(q.options.get(j))).append("</span></label>");
            }
            qHtml.append("</div>");
        }
        int totalQs = qs.size();
        double firstPct = (100.0 / totalQs);
        String js = "<script>"
            + "let currentQ = 0; const totalQs = " + totalQs + "; let timeLeft = 25; let timerInterval;"
            + "function startTimer(){clearInterval(timerInterval);timeLeft=25;updateTimerDisplay();timerInterval=setInterval(function(){timeLeft--;updateTimerDisplay();if(timeLeft<=0){clearInterval(timerInterval);forceNext();}},1000);}"
            + "function updateTimerDisplay(){var el=document.getElementById('timer_'+currentQ);if(el){el.innerText=timeLeft+'s';if(timeLeft<=5){el.classList.add('text-pink-400','animate-pulse');}else{el.classList.remove('text-pink-400','animate-pulse');}}}"
            + "function showQuestion(index){document.querySelectorAll('.question-step').forEach(function(el){el.classList.add('hidden');el.style.opacity='0';el.style.transform='translateY(10px)';});var q=document.getElementById('question_'+index);q.classList.remove('hidden');q.offsetHeight;q.style.opacity='1';q.style.transform='translateY(0)';document.getElementById('progress-bar').style.width=((index+1)/totalQs*100)+'%';document.getElementById('progress-text').innerText='Q '+(index+1)+' / '+totalQs;if(index===totalQs-1){document.getElementById('next-btn').classList.add('hidden');document.getElementById('submit-btn').classList.remove('hidden');}else{document.getElementById('next-btn').classList.remove('hidden');document.getElementById('submit-btn').classList.add('hidden');}startTimer();}"
            + "function nextQuestion(){var selected=document.querySelector('input[name=\"q'+currentQ+'\"]:checked');if(!selected){alert('Please select an option before proceeding!');return;}forceNext();}"
            + "function forceNext(){if(currentQ<totalQs-1){currentQ++;showQuestion(currentQ);}}"
            + "document.addEventListener('DOMContentLoaded',function(){showQuestion(0);document.getElementById('quiz-form').addEventListener('submit',function(){clearInterval(timerInterval);});});"
            + "</script>";
        String body = "<div class='max-w-3xl mx-auto animate-fade-in'>"
            + "<div class='mb-6 flex justify-between items-center'>"
            + "<h1 class='text-2xl font-extrabold text-cyan-200 tracking-tight'>Final Assessment</h1>"
            + "<span class='bg-cyan-900/50 text-cyan-400 px-3 py-1 rounded-full text-xs font-bold border border-cyan-500/20' id='progress-text'>Q 1 / " + totalQs + "</span></div>"
            + "<div class='w-full bg-cyan-900/20 rounded-full h-2.5 mb-8 overflow-hidden border border-cyan-500/20'>"
            + "<div id='progress-bar' class='bg-gradient-to-r from-cyan-400 to-pink-500 h-2.5 rounded-full transition-all duration-500' style='width: " + firstPct + "%;'></div></div>"
            + "<form id='quiz-form' method='post' action='/quiz/submit'>"
            + "<input type='hidden' name='courseId' value='" + c.id + "'><input type='hidden' name='_csrf' value='" + auth.csrf() + "'>"
            + qHtml.toString()
            + "<div class='flex justify-end mt-8 mb-10'>"
            + "<button type='button' id='next-btn' onclick='nextQuestion()' class='btn-neon text-white px-8 py-3 rounded-[1rem] font-bold text-sm shadow-lg hover:scale-105 transition'>Next Question</button>"
            + "<button type='submit' id='submit-btn' class='hidden bg-gradient-to-r from-emerald-500 to-emerald-700 hover:from-emerald-400 hover:to-emerald-600 text-white px-8 py-3 rounded-[1rem] font-bold text-sm shadow-[0_0_20px_rgba(16,185,129,0.4)]'>Submit Final Quiz</button>"
            + "</div></form></div>" + js;
        sendHtml(ex, 200, page("Quiz", body, auth));
    }

    static void renderProfile(HttpExchange ex, AuthCtx auth) throws IOException {
        String initial = (auth.user.fullName != null && !auth.user.fullName.isEmpty()) ? auth.user.fullName.substring(0,1).toUpperCase() : "U";
        String body = "<div class='animate-fade-in card-neon max-w-2xl mx-auto p-8 rounded-[2rem] shadow-[0_0_30px_rgba(16,185,129,0.2)]'>"
            + "<div class='flex items-center gap-6 mb-8'><div class='w-20 h-20 bg-gradient-to-br from-cyan-500 to-emerald-500 rounded-full flex items-center justify-center text-3xl font-bold text-white shadow-[0_0_30px_rgba(6,182,212,0.4)] animate-float'>" + initial + "</div>"
            + "<div><h1 class='text-2xl font-bold text-cyan-200'>" + esc(auth.user.fullName) + "</h1><p class='text-cyan-400 text-sm'>" + esc(auth.user.email) + "</p><div class='mt-2 inline-flex items-center gap-1'><span class='w-2 h-2 bg-emerald-500 rounded-full animate-pulse'></span><span class='text-xs text-emerald-400 font-medium'>Active Student</span></div></div></div>"
            + "<a href='/logout' class='inline-flex items-center gap-2 bg-pink-900/20 hover:bg-pink-900/40 text-pink-400 px-6 py-3 rounded-[1rem] text-sm font-medium transition'>Log Out <span>→</span></a></div>";
        sendHtml(ex, 200, page("Profile", body, auth));
    }

    static void showCertificate(HttpExchange ex, AuthCtx auth) throws IOException {
        long cid; try { cid = Long.parseLong(queryParam(ex, "id")); } catch (Exception e) { redirect(ex, "/dashboard"); return; }
        Course c; Enrollment e; synchronized (LOCK) { c = state.courses.stream().filter(x -> x.id == cid).findFirst().orElse(null); e = state.enrollments.stream().filter(x -> x.userId == auth.user.id && x.courseId == cid).findFirst().orElse(null); }
        if (c == null || e == null || !e.certificateIssued) {
            sendHtml(ex, 403, page("Locked", "<div class='animate-fade-in text-center mt-20'><div class='w-16 h-16 bg-pink-500/20 rounded-full flex items-center justify-center mx-auto mb-6 text-pink-500 text-3xl'>🔒</div><h1 class='text-2xl text-pink-500 font-bold mb-4'>Certificate Locked</h1><p class='text-cyan-300 mb-8'>Pass the quiz with 70% to unlock your certificate.</p><a href='/dashboard' class='btn-neon inline-block px-8 py-3 rounded-xl'>Back to Dashboard</a></div>", auth));
            return;
        }
        String date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        String certId = "GI-" + e.userId + "-" + e.courseId + "-" + (System.currentTimeMillis()%100000);
        String logoImg = "";
        if (logoBase64Bytes.length > 0) {
            String b64 = Base64.getEncoder().encodeToString(logoBase64Bytes);
            logoImg = "<img src='data:image/png;base64," + b64 + "' class='w-16 h-16 mb-4 shadow-[0_0_20px_rgba(16,185,129,0.6)]'>";
        }
        String certHtml = "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>Certificate - " + esc(auth.user.fullName) + "</title>"
            + "<script src='https://cdn.tailwindcss.com'></script>"
            + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&family=Playfair+Display:ital,wght@0,700;0,900;1,400&display=swap' rel='stylesheet'>"
            + "<style>"
            + "body { font-family: 'Inter', sans-serif; background: radial-gradient(circle at center, #0f172a 0%, #020617 100%); display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; padding: 20px; }"
            + ".cert-container { background: #ffffff; width: 100%; max-width: 1100px; position: relative; border-radius: 8px; overflow: hidden; box-shadow: 0 25px 80px rgba(16,185,129,0.3); animation: certFade 1s ease-out; }"
            + "@keyframes certFade { from { opacity: 0; transform: scale(0.95); } to { opacity: 1; transform: scale(1); } }"
            + ".gold-border { position: absolute; inset: 0; padding: 18px; pointer-events: none; z-index: 5; }"
            + ".gold-border-inner { position: relative; width: 100%; height: 100%; border: 3px solid #10b981; }"
            + ".gold-border-mid { position: absolute; top: 10px; left: 10px; right: 10px; bottom: 10px; border: 1px solid #34d399; }"
            + ".corner { position: absolute; width: 45px; height: 45px; }"
            + ".corner-tl { top: 18px; left: 18px; border-top: 5px solid #10b981; border-left: 5px solid #10b981; }"
            + ".corner-tr { top: 18px; right: 18px; border-top: 5px solid #10b981; border-right: 5px solid #10b981; }"
            + ".corner-bl { bottom: 18px; left: 18px; border-bottom: 5px solid #10b981; border-left: 5px solid #10b981; }"
            + ".corner-br { bottom: 18px; right: 18px; border-bottom: 5px solid #10b981; border-right: 5px solid #10b981; }"
            + ".watermark { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; opacity: 0.05; pointer-events: none; font-family: 'Playfair Display', serif; font-size: 220px; font-weight: 900; color: #10b981; z-index: 1; }"
            + ".content { padding: 70px 80px; position: relative; z-index: 2; }"
            + ".cert-seal { position: absolute; top: 40px; right: 60px; width: 120px; height: 120px; animation: sealFloat 5s ease-in-out infinite; z-index: 3; }"
            + "@keyframes sealFloat { 0%, 100% { transform: rotate(-10deg) translateY(0); } 50% { transform: rotate(-10deg) translateY(-10px); } }"
            + ".seal-circle { width: 120px; height: 120px; border-radius: 50%; border: 3px solid #10b981; background: radial-gradient(circle, #d1fae5 0%, #10b981 100%); display: flex; align-items: center; justify-content: center; flex-direction: column; box-shadow: 0 6px 20px rgba(16,185,129,0.4); transform: rotate(-10deg); }"
            + ".ribbon { position: absolute; bottom: -18px; left: 50%; transform: translateX(-50%); width: 35px; height: 45px; background: linear-gradient(to bottom, #10b981, #047857); clip-path: polygon(0 0, 100% 0, 100% 100%, 50% 85%, 0 100%); z-index: 4; }"
            + "@media print { body { background-color: white; padding: 0; } .cert-container { box-shadow: none; max-width: 100%; } .print-btn { display: none; } }"
            + "@media (max-width: 768px) { .content { padding: 40px 25px; } .cert-seal { position: static; margin: 0 auto 30px; } .watermark { font-size: 120px; } }"
            + "</style></head>"
            + "<body><div class='cert-container'>"
            + "<div class='watermark'>GI</div>"
            + "<div class='gold-border'><div class='gold-border-inner'><div class='gold-border-mid'></div>"
            + "<div class='corner corner-tl'></div><div class='corner corner-tr'></div><div class='corner corner-bl'></div><div class='corner corner-br'></div></div></div>"
            + "<div class='content'>"
            + "<div class='cert-seal'><div class='seal-circle'>"
            + "<svg class='w-12 h-12 text-emerald-600 mb-2' fill='currentColor' viewBox='0 0 24 24'><path d='M12 2L15 8L22 9L17 14L18 21L12 18L6 21L7 14L2 9L9 8Z'></path></svg>"
            + "<div class='text-[10px] font-bold text-emerald-800 tracking-widest'>VERIFIED</div></div><div class='ribbon'></div></div>"
            + logoImg
            + "<div class='flex items-center gap-3 mb-10 animate-fade-in' style='animation-delay:0.2s'>"
            + "<div class='w-14 h-14 bg-gradient-to-br from-cyan-500 to-emerald-500 rounded-[1rem] flex items-center justify-center shadow-[0_0_20px_rgba(6,182,212,0.4)]'>"
            + "<svg class='w-8 h-8 text-white' fill='none' stroke='currentColor' viewBox='0 0 24 24'><path stroke-linecap='round' stroke-linejoin='round' stroke-width='2.5' d='M12 14l9-5-9-5-9 5 9 5z'></path></svg></div>"
            + "<div><div class='text-2xl md:text-3xl font-black text-cyan-900 tracking-tight'>GHOURI INSTITUTE</div>"
            + "<div class='text-[11px] font-medium text-cyan-700 tracking-[0.35em] uppercase'>Excellence in Education</div></div></div>"
            + "<div class='text-center mb-8 animate-fade-in' style='animation-delay:0.4s'>"
            + "<div class='text-xs font-bold text-emerald-600 tracking-[0.5em] uppercase mb-3'>Certificate of Completion</div>"
            + "<div class='w-24 h-1 bg-gradient-to-r from-transparent via-emerald-500 to-transparent mx-auto'></div></div>"
            + "<div class='text-center my-12 animate-fade-in' style='animation-delay:0.6s'>"
            + "<div class='text-sm italic text-cyan-400 mb-4'>This certificate is proudly presented to</div>"
            + "<div class='text-5xl md:text-7xl font-black text-cyan-200 mb-6' style='font-family: Playfair Display, serif; letter-spacing: -0.03em; border-bottom: 3px solid #34d399; padding-bottom: 16px; display: inline-block;'>" + esc(auth.user.fullName) + "</div>"
            + "<div class='text-sm text-cyan-300 mb-6'>for successfully completing the professional course</div>"
            + "<div class='text-2xl md:text-3xl font-bold text-emerald-400 mt-4'>" + esc(c.title) + "</div>"
            + "<div class='text-xs text-cyan-400 italic mt-4'>with distinction and academic excellence</div></div>"
            + "<div class='flex flex-col md:flex-row justify-between items-center md:items-end mt-16 pt-8 border-t-3 border-cyan-500/20 gap-8 animate-fade-in' style='animation-delay:0.8s'>"
            + "<div class='text-center md:text-left'>"
            + "<div class='text-sm font-bold text-cyan-200'>" + date + "</div>"
            + "<div class='text-[11px] text-cyan-400 uppercase tracking-widest mt-2'>Date of Issue</div>"
            + "<div class='text-[11px] text-cyan-500 mt-3 font-mono'>Certificate ID: " + certId + "</div></div>"
            + "<div class='text-center md:text-right'>"
            + "<div class='w-48 border-t-3 border-cyan-400 mx-auto mb-3'></div>"
            + "<div class='text-sm font-bold text-cyan-200 mt-1'>M. Ghouri (Arham)</div>"
            + "<div class='text-[11px] text-cyan-400 uppercase tracking-widest mt-1'>Founder &amp; CEO</div></div></div>"
            + "</div></div>"
            + "<div class='fixed bottom-4 left-0 w-full text-center print-btn animate-fade-in' style='animation-delay:1.2s'>"
            + "<button onclick='window.print()' class='bg-gradient-to-r from-emerald-500 to-emerald-700 hover:from-emerald-400 hover:to-emerald-600 text-white px-10 py-4 rounded-full font-bold shadow-[0_0_30px_rgba(16,185,129,0.4)] text-sm transition-all hover:scale-105'>Download / Print PDF</button></div>"
            + "<canvas id='confetti' style='position:fixed;top:0;left:0;width:100%;height:100%;pointer-events:none;z-index:9999;display:none;'></canvas>"
            + "<script src='https://cdn.jsdelivr.net/npm/canvas-confetti@1.6.0/dist/confetti.browser.min.js'></script>"
            + "<script>setTimeout(function(){var c=document.getElementById('confetti');if(c)c.style.display='block';confetti({particleCount:200,spread:90,origin:{y:0.4},colors:['#10b981','#22d3ee','#f472b6','#ffffff']});},300);</script>"
            + "</body></html>";
        sendHtml(ex, 200, certHtml);
    }

    static void showLogin(HttpExchange ex, AuthCtx auth) throws IOException {
        if (auth.loggedIn()) { redirect(ex, "/"); return; }
        String rememberedEmail = getCookieValue(ex, "LMS_REMEMBER");
        String b = "<div class='animate-fade-in card-neon max-w-md mx-auto mt-10 p-8 md:p-10 rounded-[2rem] shadow-[0_0_40px_rgba(6,182,212,0.2)] mx-4 md:mx-auto relative overflow-hidden'>"
            + "<div class='glass-photo opacity-60' style=\"background-image:url('/assets/bg-hero.jpg');\"></div>"
            + "<div class='glass-veil'></div>"
            + "<div class='relative z-10'>"
            + "<div class='text-center mb-8'>"
            + logoTag()
            + "<div class='mt-4'><h1 class='text-2xl font-extrabold text-cyan-200 tracking-tight'>GHOURI INSTITUTE</h1><p class='text-xs text-cyan-400 mt-1 tracking-wider'>World-Class Learning Portal</p></div></div>"
            + "<a href='/auth/google' class='w-full flex items-center justify-center gap-3 bg-white/10 text-cyan-200 py-3.5 rounded-[1rem] font-bold mb-6 text-sm shadow-[0_0_20px_rgba(6,182,212,0.2)] hover:shadow-[0_0_30px_rgba(6,182,212,0.4)] transition-all border border-cyan-500/30 hover:bg-cyan-500/10'>"
            + "<svg class='w-5 h-5' viewBox='0 0 48 48'><path fill='#EA4335' d='M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z'/><path fill='#4285F4' d='M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z'/><path fill='#FBBC05' d='M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z'/><path fill='#34A853' d='M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z'/></svg> Continue with Google</a>"
            + "<div class='flex items-center gap-4 mb-6'><div class='h-px bg-cyan-500/20 flex-1'></div><span class='text-xs text-cyan-400 font-medium'>Or continue with email</span><div class='h-px bg-cyan-500/20 flex-1'></div></div>"
            + "<form method='post' action='/login' class='space-y-4'>"
            + "<div><label class='block text-sm font-medium text-cyan-400 mb-2'>Email Address</label><input name='email' type='email' placeholder='you@email.com' required value='" + rememberedEmail + "' class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-[1rem] text-cyan-100 text-sm outline-none focus:ring-2 focus:ring-cyan-500/50 focus:border-cyan-500 transition-all'></div>"
            + "<div><label class='block text-sm font-medium text-cyan-400 mb-2'>Password</label><input name='password' type='password' placeholder='••••••••' required class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-[1rem] text-cyan-100 text-sm outline-none focus:ring-2 focus:ring-cyan-500/50 focus:border-cyan-500 transition-all'></div>"
            + "<button class='btn-neon w-full py-3.5 rounded-[1rem] font-bold text-sm shadow-lg hover:shadow-[0_0_30px_rgba(16,185,129,0.4)] transition-all'>Sign In</button></form>"
            + "<p class='text-center text-xs text-cyan-400 mt-8'>New to Ghouri Institute? <a href='/signup' class='text-emerald-400 hover:text-emerald-200 transition font-medium'>Create your account</a></p></div></div>";
        sendHtml(ex, 200, page("Login", b, auth));
    }

    static void showSignup(HttpExchange ex, AuthCtx auth) throws IOException {
        if (auth.loggedIn()) { redirect(ex, "/"); return; }
        String b = "<div class='animate-fade-in card-neon max-w-md mx-auto mt-10 p-8 md:p-10 rounded-[2rem] shadow-[0_0_40px_rgba(6,182,212,0.2)] mx-4 md:mx-auto relative overflow-hidden'>"
            + "<div class='glass-photo opacity-60' style=\"background-image:url('/assets/bg-hero.jpg');\"></div>"
            + "<div class='glass-veil'></div>"
            + "<div class='relative z-10'>"
            + "<div class='text-center mb-8'>"
            + logoTag()
            + "<div class='mt-4'><h1 class='text-2xl font-extrabold text-cyan-200 tracking-tight'>GHOURI INSTITUTE</h1><p class='text-xs text-cyan-400 mt-1 tracking-wider'>Join thousands of learners</p></div></div>"
            + "<a href='/auth/google' class='w-full flex items-center justify-center gap-3 bg-white/10 text-cyan-200 py-3.5 rounded-[1rem] font-bold mb-6 text-sm shadow-[0_0_20px_rgba(6,182,212,0.2)] hover:shadow-[0_0_30px_rgba(6,182,212,0.4)] transition-all border border-cyan-500/30 hover:bg-cyan-500/10'>"
            + "<svg class='w-5 h-5' viewBox='0 0 48 48'><path fill='#EA4335' d='M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z'/><path fill='#4285F4' d='M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z'/><path fill='#FBBC05' d='M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z'/><path fill='#34A853' d='M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z'/></svg> Sign up with Google</a>"
            + "<div class='flex items-center gap-4 mb-6'><div class='h-px bg-cyan-500/20 flex-1'></div><span class='text-xs text-cyan-400 font-medium'>Or create account with email</span><div class='h-px bg-cyan-500/20 flex-1'></div></div>"
            + "<form method='post' action='/signup' class='space-y-4'>"
            + "<input name='fullName' placeholder='Full Name' required class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-[1rem] text-cyan-100 text-sm focus:ring-2 focus:ring-cyan-500/50 outline-none transition-all'>"
            + "<input name='email' type='email' placeholder='Email Address' required class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-[1rem] text-cyan-100 text-sm focus:ring-2 focus:ring-cyan-500/50 outline-none transition-all'>"
            + "<input name='password' type='password' placeholder='Password (min 6 characters)' required class='w-full px-4 py-3 bg-black/40 border border-cyan-500/30 rounded-[1rem] text-cyan-100 text-sm focus:ring-2 focus:ring-cyan-500/50 outline-none transition-all'>"
            + "<button class='btn-neon w-full py-3.5 rounded-[1rem] font-bold text-sm shadow-lg hover:shadow-[0_0_30px_rgba(16,185,129,0.4)] transition-all'>Create Account</button></form>"
            + "<p class='text-center text-xs text-cyan-400 mt-8'>Already have an account? <a href='/login' class='text-emerald-400 hover:text-emerald-200 transition font-medium'>Sign in</a></p></div></div>";
        sendHtml(ex, 200, page("Sign up", b, auth));
    }

    static String page(String title, String body, AuthCtx auth) {
        String nav = "";
        if (auth != null && auth.loggedIn()) {
            if (auth.isAdmin()) {
                nav += "<a class='text-xs md:text-sm text-cyan-400 hover:text-cyan-200 hidden md:block transition' href='/admin'>Admin Panel</a>";
            } else {
                nav += "<a class='text-xs md:text-sm text-cyan-400 hover:text-cyan-200 hidden sm:block transition' href='/catalog'>Course Catalog</a>";
                nav += "<a class='text-xs md:text-sm text-cyan-400 hover:text-cyan-200 transition' href='/dashboard'>My Learning</a>";
            }
            nav += "<a class='text-xs md:text-sm text-pink-400 hover:text-pink-200 transition' href='/logout'>Sign Out</a>";
        } else {
            nav += "<a class='text-xs md:text-sm text-cyan-400 hover:text-cyan-200 transition' href='/login'>Sign In</a>";
            nav += "<a class='bg-black/40 text-cyan-200 px-3 py-1.5 rounded-[1rem] font-medium text-xs md:text-sm shadow-[0_0_15px_rgba(6,182,212,0.2)] hover:shadow-[0_0_25px_rgba(6,182,212,0.4)] transition border border-cyan-500/30' href='/signup'>Join Free</a>";
        }
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>" + esc(title) + " | Ghouri Institute</title>"
            + "<script src='https://cdn.tailwindcss.com'></script>"
            + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800;900&display=swap' rel='stylesheet'>"
            + "<style>"
            + "@import url('https://fonts.googleapis.com/css2?family=Playfair+Display:ital,wght@0,700;0,900;1,400&display=swap');"
            + "body { font-family: 'Inter', system-ui, sans-serif; background: radial-gradient(circle at center, #0f172a 0%, #020617 100%); color: #f8fafc; overflow-x: hidden; }"
            + ".bg-scene { position: fixed; top: 0; left: 0; width: 100vw; height: 100vh; z-index: -2; overflow: hidden; }"
            + ".bg-photo { position: absolute; inset: -10%; background-image: url('/assets/bg-hero.jpg'); background-size: cover; background-position: center; filter: blur(26px) saturate(150%) brightness(0.62); transform: scale(1.15); animation: drift 50s ease-in-out infinite alternate; }"
            + ".bg-veil { position: absolute; inset: 0; background: radial-gradient(circle at 50% 0%, rgba(15,23,42,0.30) 0%, rgba(2,6,23,0.88) 62%), linear-gradient(180deg, rgba(2,6,23,0.72) 0%, rgba(2,6,23,0.96) 100%); }"
            + "@keyframes drift { 0% { transform: scale(1.15) translate3d(0,0,0); } 100% { transform: scale(1.24) translate3d(-2.5%,-1.8%,0); } }"
            + ".bg-orbs { position: absolute; top: 0; left: 0; width: 100%; height: 100%; overflow: hidden; }"
            + ".orb { position: absolute; border-radius: 50%; filter: blur(100px); opacity: 0.35; animation: float 18s infinite alternate; }"
            + ".orb-1 { background: #22d3ee; width: 600px; height: 600px; top: -180px; left: -180px; }"
            + ".orb-2 { background: #f472b6; width: 500px; height: 500px; bottom: -120px; right: -120px; animation-delay: -6s; }"
            + ".orb-3 { background: #10b981; width: 400px; height: 400px; top: 35%; left: 45%; animation: float 22s infinite alternate; opacity: 0.25; animation-delay: -10s; }"
            + ".orb-4 { background: #8b5cf6; width: 300px; height: 300px; bottom: 20%; left: 10%; animation: float 16s infinite alternate; opacity: 0.2; animation-delay: -14s; }"
            + "@keyframes float { 0% { transform: translate(0, 0) scale(1); } 100% { transform: translate(80px, 80px) scale(1.1); } }"
            + "@keyframes fadeIn { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: translateY(0); } }"
            + "@keyframes bounce { 0%, 100% { transform: translateY(-25%) animation-timing-function: cubic-bezier(0.8, 0, 1, 1); } 50% { transform: translateY(0) animation-timing-function: cubic-bezier(0, 0, 0.2, 1); } }"
            + "@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }"
            + ".animate-fade-in { animation: fadeIn 0.7s ease-out both; }"
            + ".animate-slide-up { animation: fadeIn 0.8s ease-out both; }"
            + ".animate-float { animation: float 5s ease-in-out infinite; }"
            + ".animate-bounce { animation: bounce 1.5s infinite; }"
            + ".animate-pulse { animation: pulse 2s infinite; }"
            + ".glass-photo { position: absolute; inset: 0; background-size: cover; background-position: center; filter: blur(7px) saturate(135%) brightness(0.65); transform: scale(1.06); }"
            + ".glass-veil { position: absolute; inset: 0; background: linear-gradient(90deg, rgba(2,6,23,0.92) 0%, rgba(2,6,23,0.62) 55%, rgba(2,6,23,0.30) 100%); }"
            + ".card-neon { background: rgba(2,6,23,0.85); backdrop-filter: blur(20px) saturate(180%); border: 1px solid rgba(6,182,212,0.2); transition: all 0.4s cubic-bezier(0.4, 0, 0.2, 1); }"
            + ".card-neon:hover { border-color: rgba(6,182,212,0.5); transform: translateY(-4px); box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.6); }"
            + ".btn-neon { background: linear-gradient(135deg, #06b6d4 0%, #10b981 100%); transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1); color: white; border: 1px solid rgba(255, 255, 255, 0.1); box-shadow: 0 10px 25px -5px rgba(6,182,212,0.2); }"
            + ".btn-neon:hover { transform: translateY(-3px) scale(1.02); box-shadow: 0 20px 35px -8px rgba(6,182,212,0.4); filter: brightness(1.1); }"
            + ".btn-neon:active { transform: translateY(0) scale(0.98); }"
            + "::-webkit-scrollbar { width: 6px; } ::-webkit-scrollbar-track { background: #0f172a; } ::-webkit-scrollbar-thumb { background: #22d3ee; border-radius: 4px; } ::-webkit-scrollbar-thumb:hover { background: #67e8f9; }"
            + ".question-step { animation: fadeIn 0.35s ease-out both; }"
            + ".hidden { display: none !important; }"
            + ".shadow-inner { box-shadow: inset 0 2px 4px 0 rgba(0, 0, 0, 0.2); }"
            + "</style></head>"
            + "<body class='antialiased min-h-screen flex flex-col selection:bg-cyan-500 selection:text-white'>"
            + "<div class='bg-scene'><div class='bg-photo'></div><div class='bg-veil'></div><div class='bg-orbs'><div class='orb orb-1'></div><div class='orb orb-2'></div><div class='orb orb-3'></div><div class='orb orb-4'></div></div></div>"
            + "<header class='border-b border-cyan-500/20 bg-black/40 backdrop-blur-md sticky top-0 z-50'><div class='max-w-7xl mx-auto h-16 md:h-20 flex items-center justify-between px-4 md:px-8'>"
            + "<a class='flex items-center gap-3' href='/'><div class='w-8 h-8 bg-gradient-to-br from-cyan-500 to-emerald-500 rounded-[1rem] flex items-center justify-center shadow-[0_0_20px_rgba(6,182,212,0.4)]'><svg class='w-5 h-5 text-white' fill='none' stroke='currentColor' viewBox='0 0 24 24'><path stroke-linecap='round' stroke-linejoin='round' stroke-width='2' d='M12 14l9-5-9-5-9 5 9 5z'></path></svg></div><div><div class='text-lg font-black text-cyan-200 tracking-tight'>GHOURI INSTITUTE</div><div class='text-[10px] text-cyan-400 uppercase tracking-wider mt-0.5'>Learning Portal</div></div></a>"
            + "<nav class='flex items-center gap-3 md:gap-6'>" + nav + "</nav></div></header>"
            + "<main class='flex-grow max-w-7xl mx-auto w-full px-4 md:px-8 py-8 md:py-16 relative z-10'>" + body + "</main>"
            + "<div id='toast' class='fixed bottom-6 right-6 z-50'></div>"
            + "<script>"
            + "function toast(msg,type){var el=document.createElement('div');el.className='animate-slide-up px-6 py-3 rounded-[1rem] text-white text-sm font-medium shadow-xl '+(type==='success'?'bg-emerald-500':'bg-cyan-600');el.innerText=msg;document.getElementById('toast').appendChild(el);setTimeout(function(){el.style.opacity='0';el.style.transform='translateY(10px)';setTimeout(function(){el.remove();},300);},2500);}"
            + "</script>"
            + "</body></html>";
    }

    static void sendHtml(HttpExchange ex, int st, String h) throws IOException {
        byte[] b = h.getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(st, b.length); try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
    static void redirect(HttpExchange ex, String to) throws IOException { ex.getResponseHeaders().add("Location", to); ex.sendResponseHeaders(302, -1); }
    static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    static String queryParam(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getQuery(); if (q == null) return null;
        for (String p : q.split("&")) { int i = p.indexOf('='); if (i != -1 && p.substring(0, i).equals(name)) return URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8); else if (i == -1 && p.equals(name)) return ""; }
        return null;
    }
    static Map<String, String> parseForm(HttpExchange ex) {
        try {
            String s = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> m = new HashMap<>();
            if (s == null || s.trim().isEmpty()) return m;
            for (String p : s.split("&")) { int i = p.indexOf('='); if (i > 0) m.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8)); }
            return m;
        } catch (Exception e) { return Collections.emptyMap(); }
    }
}