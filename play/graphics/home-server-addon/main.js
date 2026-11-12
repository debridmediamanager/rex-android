// A plain file server whose /videos.json lists [{"id", "title", "year", "files": {"1080": "name.mp4", ...}}].
const base = (ctx) => (/^https?:\/\//.test(ctx.config.server) ? ctx.config.server : `http://${ctx.config.server}`).replace(/\/$/, "");

// The best copy at or below the chosen quality, else the smallest there is.
function pick(files, quality) {
  const sizes = Object.keys(files).map(Number).sort((a, b) => b - a);
  const size = sizes.find((one) => one <= Number(quality)) ?? sizes[sizes.length - 1];
  return { size, file: files[size] };
}

async function listing(ctx) {
  const answer = await fetch(`${base(ctx)}/videos.json`);
  if (!answer.ok) throw new Error(`the server answered ${answer.status}`);
  return answer.json();
}

export default {
  async items(ctx) {
    const videos = await listing(ctx);
    console.log(`${base(ctx)} lists ${videos.length} videos`);
    return videos.map((video) => {
      const { size, file } = pick(video.files, ctx.config.quality);
      console.log(`${video.title}: ${size}p`);
      return { id: video.id, kind: "movie", path: `home-server/${video.id}/${file}`,
               display: { title: video.title, date: String(video.year) } };
    });
  },

  async open(id, ctx) {
    const video = (await listing(ctx)).find((one) => one.id === id);
    if (!video) throw new Error(`the server no longer lists ${id}`);
    return { url: `${base(ctx)}/${encodeURIComponent(pick(video.files, ctx.config.quality).file)}` };
  },
};
