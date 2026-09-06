package se.l4.exofind.engine.index.locales;

/**
 * The light stemmers of the Indic languages Lucene ships none for, one
 * {@link SuffixStemmer} per language. Urdu is among them: it writes the
 * Perso-Arabic script, but inflects the way Hindi does.
 *
 * Each of these languages inflects a noun by putting a case ending, and often
 * a plural marker before it, on the end of the word. The endings are written
 * as part of the word, so a search for the bare word misses every inflected
 * form unless the endings are cut. The tables below hold those endings, and
 * the verb endings that mark tense and agreement, in the way Lucene's own
 * Hindi stemmer does: cut the longest ending the word carries and leave the
 * rest alone. The stem that remains is not always a word - a light stemmer
 * only has to bring the forms of one word to the same string.
 *
 * The tables are written by hand from the grammar of each language and cover
 * the regular inflection. A form that changes the stem itself, or an ending
 * not listed here, is left as written and matches only itself.
 *
 * The minimum stem length of each step is in characters of the script, where
 * a vowel sign is a character of its own, so a two-character minimum keeps a
 * consonant and its vowel sign as the shortest stem.
 */
public final class IndicStemmers {
	private IndicStemmers() {
	}

	/**
	 * Marathi. A noun takes an oblique ending - {@code घर} to {@code घरा}, or
	 * {@code घरां} in the plural - and the postposition is written onto it:
	 * {@code घरात} in the house, {@code घराला} to the house, {@code घरांमध्ये}
	 * in the houses. The first step cuts the postposition or a verb ending,
	 * the second the oblique or plural marker it sat on, and a bare plural or
	 * gender vowel, so {@code मुलगा}, {@code मुलगे} and {@code मुलग्याला} end
	 * at {@code मुलग}.
	 */
	public static final SuffixStemmer MARATHI = SuffixStemmer.create()
		.step(2,
			// Postpositions
			"मध्ये", "पासून", "प्रमाणे", "बरोबर", "पेक्षा", "विषयी", "बद्दल",
			"साठी", "कडून", "कडे", "तून", "हून", "मुळे",
			"च्या", "चा", "ची", "चे", "ला", "ना", "ने", "नी", "वर", "शी",
			// Verb endings
			"णारा", "णारी", "णारे", "णार", "ायला", "ावे", "ावा", "ावी",
			"तात", "तोस", "तेस", "ल्या", "ून", "तो", "ते", "ती", "ली", "ले", "णे"
		)
		// The locative alone is one letter, so it needs more of the word left
		.step(3, "त")
		.step(2, "्यां", "्या", "ां", "ीं", "ूं", "ा", "ी", "े", "ू")
		.build();

	/**
	 * Gujarati. The postpositions are written onto the noun - {@code ઘરમાં}
	 * in the house, {@code ઘરથી} from the house, {@code છોકરાને} to the boy -
	 * and the plural is {@code ો} or {@code ઓ} after a vowel. The first step
	 * cuts a postposition or a verb ending, the second the plural marker and
	 * the gender vowel, so {@code છોકરો}, {@code છોકરા} and {@code છોકરાઓને}
	 * end at {@code છોકર}.
	 */
	public static final SuffixStemmer GUJARATI = SuffixStemmer.create()
		.step(2,
			// Postpositions
			"માંથી", "માં", "થી", "નાં", "ના", "ની", "નું", "નો", "ને", "એ",
			// Verb endings
			"વાનું", "વાના", "વાની", "વાનો", "વામાં", "વું", "વા", "વી",
			"ેલાં", "ેલા", "ેલી", "ેલું", "ેલો",
			"તાં", "તા", "તી", "તું", "તો", "યાં", "યા", "યું", "યો",
			"ીશું", "ીશ", "ીને", "ીએ", "શે", "શો"
		)
		.step(2, "ાઓ", "ીઓ", "ુઓ", "ાં", "ું", "ા", "ી", "ો", "ે", "ુ")
		.build();

	/**
	 * Punjabi in the Gurmukhi script. The postpositions are words of their
	 * own, but the noun in front of one takes an oblique ending, and the
	 * plural oblique carries a nasal: {@code ਮੁੰਡਾ} the boy, {@code ਮੁੰਡੇ}
	 * before a postposition, {@code ਮੁੰਡਿਆਂ} the boys before one, and
	 * {@code ਘਰਾਂ}, {@code ਘਰੋਂ} for a house. The first step cuts a verb
	 * ending, the second the oblique or plural marker and the gender vowel.
	 */
	public static final SuffixStemmer PUNJABI = SuffixStemmer.create()
		.step(2,
			"ਾਂਗੀਆਂ", "ਣਗੀਆਂ", "ਦੀਆਂ", "ਣੀਆਂ",
			"ਾਂਗਾ", "ਾਂਗੇ", "ਾਂਗੀ", "ਏਗਾ", "ਏਗੀ", "ਏਗੇ", "ਣਗੇ", "ਣਗੀ",
			"ਦਿਆਂ", "ਦਾ", "ਦੀ", "ਦੇ", "ਣਾ", "ਣੀ", "ਣੇ"
		)
		.step(2, "ਿਆਂ", "ੀਆਂ", "ੂਆਂ", "ਿਆ", "ਾਂ", "ੋਂ", "ੀਂ", "ਾ", "ੀ", "ੇ", "ੂ")
		.build();

	/**
	 * Kannada. Case and number are agglutinated onto the noun, with a
	 * joining consonant that depends on how the noun ends: {@code ಮರ} a tree
	 * gives {@code ಮರದಲ್ಲಿ} in the tree and {@code ಮರಗಳಲ್ಲಿ} in the trees,
	 * {@code ಮನೆ} a house gives {@code ಮನೆಯಲ್ಲಿ}, and {@code ಹುಡುಗ} a boy
	 * gives {@code ಹುಡುಗನಿಗೆ} to the boy and {@code ಹುಡುಗರು} boys. The first
	 * step cuts the whole of a plural and case ending, or a verb ending; the
	 * second the {@code ು} an -u noun ends in, which its oblique forms drop.
	 */
	public static final SuffixStemmer KANNADA = SuffixStemmer.create()
		.step(2,
			// Plural with its case
			"ಗಳೊಂದಿಗೆ", "ಗಳಿಗಾಗಿ", "ಗಳಲ್ಲಿ", "ಗಳನ್ನು", "ಗಳಿಂದ", "ಗಳಿಗೆ",
			"ಗಳು", "ಗಳೇ", "ಗಳೂ", "ಗಳ",
			// Singular case, with the joining consonant
			"ಿನಲ್ಲಿ", "ದಲ್ಲಿ", "ನಲ್ಲಿ", "ಯಲ್ಲಿ", "ರಲ್ಲಿ", "ಲ್ಲಿ",
			"ವನ್ನು", "ನನ್ನು", "ಯನ್ನು", "ರನ್ನು", "ನ್ನು",
			"ಿನಿಂದ", "ದಿಂದ", "ನಿಂದ", "ಯಿಂದ", "ರಿಂದ", "ಿಂದ",
			"ೊಂದಿಗೆ", "ಕ್ಕಾಗಿ", "ಗಾಗಿ", "ನಿಗೆ", "ರಿಗೆ", "ಕ್ಕೆ", "ಿಗೆ", "ಗೆ",
			"ಿನ", "ನು", "ರು", "ವು",
			// Verb endings
			"ುತ್ತಾರೆ", "ುತ್ತಾನೆ", "ುತ್ತಾಳೆ", "ುತ್ತೇನೆ", "ುತ್ತೀರಿ", "ುತ್ತೇವೆ",
			"ುತ್ತದೆ", "ುತ್ತಿದೆ", "ಿದ್ದಾರೆ", "ಿದ್ದಾನೆ", "ಿದ್ದಾಳೆ",
			"ಿದರು", "ಿದನು", "ಿದಳು", "ುವುದು", "ಿದೆ", "ಿತು", "ುವ", "ಿದ", "ಲು"
		)
		// The genitive is the joining consonant alone
		.step(2, "ದ", "ನ", "ಯ", "ರ")
		.step(3, "ು")
		.build();

	/**
	 * Malayalam. Case and number are agglutinated onto the noun, and a noun
	 * in {@code ം} changes it to {@code ത്ത} before a case ending and to
	 * {@code ങ്ങൾ} in the plural: {@code മരം} a tree, {@code മരത്തിൽ} in the
	 * tree, {@code മരങ്ങൾ} trees, {@code മരങ്ങളിൽ} in the trees. Other nouns
	 * take {@code കൾ} in the plural and a case ending straight on: {@code
	 * കുട്ടി} a child, {@code കുട്ടികളുടെ} of the children. The first step
	 * cuts the plural with its case, a case ending or a verb ending; the
	 * second the {@code ം} or the {@code ്} a bare noun ends in, which the
	 * inflected forms replace.
	 */
	public static final SuffixStemmer MALAYALAM = SuffixStemmer.create()
		.step(2,
			// Plural with its case
			"ങ്ങളിലേക്ക്", "ങ്ങളിലൂടെ", "ങ്ങളുടെ", "ങ്ങളിൽ", "ങ്ങൾക്ക്",
			"ങ്ങളോട്", "ങ്ങളാൽ", "ങ്ങളും", "ങ്ങളെ", "ങ്ങൾ",
			"കളിലേക്ക്", "കളിലൂടെ", "കളുടെ", "കളിൽ", "കൾക്ക്",
			"കളോട്", "കളാൽ", "കളും", "കളെ", "കൾ",
			// Singular case, with the ത്ത a noun in ം takes
			"ത്തിലേക്ക്", "ത്തിലൂടെ", "ത്തിന്റെ", "ത്തിനു", "ത്തിന്", "ത്തിനെ",
			"ത്തിൽ", "ത്തോട്", "ത്താൽ", "ത്തെ",
			"യിലേക്ക്", "ിലേക്ക്", "ിലൂടെ", "യുടെ", "ിന്റെ", "ന്റെ", "ുടെ",
			"യിൽ", "ിൽ", "ിനോട്", "ിനാൽ", "യോട്", "ോട്", "ക്ക്", "ിനു", "ിന്",
			"ിനെ", "ാൽ", "യെ", "ും",
			// Verb endings
			"ുകയായിരുന്നു", "ുകയാണ്", "ിരുന്നു", "ുന്നു", "ുന്ന", "ിച്ചു", "ിച്ച",
			"ുക", "ാൻ", "ണം", "ിയ"
		)
		.step(2, "ം", "്", "ു")
		.build();

	/**
	 * Odia. The case endings are written onto the noun - {@code ଘରରେ} in the
	 * house, {@code ଘରକୁ} to the house, {@code ଘରର} of the house - as are the
	 * definite {@code ଟି} and the plural markers, {@code ମାନେ} for people and
	 * {@code ଗୁଡ଼ିକ} for things, which take the case ending after them:
	 * {@code ପିଲାମାନଙ୍କୁ} to the children. The first step cuts the whole of
	 * such an ending or a verb ending, the second the vowel a noun such as
	 * {@code ପିଲା} ends in, so that it meets the forms that were cut.
	 *
	 * The {@code ଡ଼} of {@code ଗୁଡ଼ିକ} is written as the letter and a nukta,
	 * which is what Unicode normalization turns the single character into
	 * before the stemmer sees the word.
	 */
	public static final SuffixStemmer ODIA = SuffixStemmer.create()
		.step(2,
			// Plural with its case
			"ମାନଙ୍କରେ", "ମାନଙ୍କୁ", "ମାନଙ୍କର", "ମାନଙ୍କ", "ମାନେ",
			"ଗୁଡ଼ିକରେ", "ଗୁଡ଼ିକୁ", "ଗୁଡ଼ିକର", "ଗୁଡ଼ିକ",
			// The honorific and the definite markers with their case
			"ଙ୍କରେ", "ଙ୍କୁ", "ଙ୍କର", "ଙ୍କ",
			"ଟିରେ", "ଟିକୁ", "ଟିର", "ଟି", "ଟାରେ", "ଟାକୁ", "ଟାର", "ଟା",
			// Case
			"ଠାରୁ", "ଠାରେ", "ରେ", "ରୁ", "କୁ",
			// Verb endings
			"ୁଥିଲା", "ୁଥିଲେ", "ୁଥିଲି", "ିଥିଲା", "ିଥିଲେ", "ୁଛନ୍ତି", "ିଛନ୍ତି",
			"ିବାକୁ", "ିବାର", "ିବାରେ", "ନ୍ତି", "ନ୍ତୁ",
			"ିଲା", "ିଲେ", "ିଲି", "ିଲୁ", "ୁଛି", "ୁଛୁ", "ିଛି", "ିବ", "ିବେ", "ିବି", "ିବା"
		)
		// The genitive alone
		.step(2, "ର")
		.step(2, "ା")
		.build();

	/**
	 * Urdu, in the Perso-Arabic script and after {@link UrduNormalizeFilter}
	 * has run, so the tables hold the Farsi yeh, the keheh and the heh goal.
	 * The postpositions are words of their own, but a noun takes an oblique
	 * or plural ending in front of one: {@code لڑکا} the boy, {@code لڑکے}
	 * the boys or the boy before a postposition, {@code لڑکوں} the boys
	 * before one; {@code کتاب} a book, {@code کتابیں} books, {@code کتابوں}
	 * books before a postposition. The first step cuts a plural ending, an
	 * Arabic plural in {@code ات}, or a verb ending; the second the vowel of
	 * gender and number, and the final heh a noun such as {@code بچہ} loses
	 * in its other forms.
	 */
	public static final SuffixStemmer URDU = SuffixStemmer.create()
		.step(2,
			// Plural and oblique
			"یوں", "یاں", "ئیں", "ئے", "یں", "وں", "ات",
			// Verb endings, and the future written onto the verb
			"وںگا", "وںگی", "یںگے", "یںگی", "ےگا", "ےگی", "ےگے",
			"تا", "تی", "تے", "نا", "نی", "نے"
		)
		.step(2, "ا", "ی", "ے", "ہ")
		.build();
}
