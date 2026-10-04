/** A run of text, with a pinyin reading when it is a single Chinese character. */
export interface RubySegment {
  text: string;
  reading: string | null;
}

const HAN = /\p{Script=Han}/u;

export function containsChinese(text: string | null | undefined): boolean {
  return !!text && HAN.test(text);
}

/**
 * Splits text into ruby segments with tone-marked pinyin for each Chinese character, e.g.
 * "我喜欢" -> [{text: "我", reading: "wǒ"}, ...]. Non-Chinese runs are kept together without a reading.
 * pinyin-pro (and its dictionary) is loaded on first use so readers who never select Chinese don't download it.
 * Readings come from pinyin-pro's word-based lookup; characters with several pronunciations can occasionally be wrong.
 */
export async function toRuby(text: string): Promise<RubySegment[]> {
  const {pinyin} = await import('pinyin-pro');
  const segments: RubySegment[] = [];
  for (const part of pinyin(text, {type: 'all'})) {
    if (part.isZh && part.pinyin) {
      segments.push({text: part.origin, reading: part.pinyin});
      continue;
    }
    const last = segments.at(-1);
    if (last && last.reading === null) {
      last.text += part.origin;
    } else {
      segments.push({text: part.origin, reading: null});
    }
  }
  return segments;
}
