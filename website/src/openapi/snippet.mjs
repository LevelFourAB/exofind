/*
 * The call, in the languages a reader is likely to make it from.
 *
 * A reader of an endpoint page is about to make the request, so the panel beside
 * the endpoint shows the request rather than the shape of it: a URL with real
 * values in its placeholders, the two headers every call carries, and the body
 * from the example on the same page. It is written to be copied and run.
 *
 * That is why only the required parameters are in it. An optional one is stated
 * in the table on the left, where it can be read with what it does; put in the
 * call it becomes a value the reader has to know to take out again - a
 * conditional write against an entity tag that is not theirs, a cursor into a
 * page they have not fetched.
 *
 * The first snippet is the request itself, written the way the front page
 * writes one: the method and the path, the headers under it, and the body after
 * a blank line. It is the shortest statement of what the endpoint wants, and it
 * is the one a reader can follow without knowing any of the four languages.
 *
 * The key is an environment variable rather than a key, in every language. A
 * page that prints a key that looks real teaches a reader to paste keys into
 * files, and the one printed here would be in every reader's shell history. The
 * HTTP snippet is not code and reads no environment, so it carries the
 * placeholder the overview page carries.
 *
 * Four languages, and they stay the four until there are client libraries to
 * show instead. A library call says more than a request does, so this module is
 * where that change lands.
 */

/** The environment variable the examples read the API key from. */
const KEY_VARIABLE = 'EXOFIND_API_KEY';

/**
 * @typedef {object} Snippet
 * @property {string} id what the tab is keyed by
 * @property {string} label what the tab is called
 * @property {string} lang how the code is highlighted
 * @property {string} code the call
 */

/**
 * The call to an endpoint, one snippet per language.
 *
 * @param {import('./spec.mjs').Operation} operation
 * @param {string} origin where the node is reached, from the document's server
 * @param {unknown} body the request body to send, or `null` for none
 * @returns {Snippet[]}
 */
export function snippetsFor(operation, origin, body) {
	const target = targetFor(operation);

	const call = {
		method: operation.method,
		target,
		url: `${origin}${target}`,
		headers: headersFor(operation, body !== null),
		body: body === null ? null : JSON.stringify(body, null, '\t')
	};

	return [
		{ id: 'http', label: 'HTTP', lang: 'http', code: http(call) },
		{ id: 'curl', label: 'curl', lang: 'bash', code: curl(call) },
		{ id: 'javascript', label: 'JavaScript', lang: 'js', code: javascript(call) },
		{ id: 'java', label: 'Java', lang: 'java', code: java(call) },
		{ id: 'go', label: 'Go', lang: 'go', code: go(call) }
	];
}

/**
 * What the call asks for: the path with its placeholders filled in, and the
 * query parameters the endpoint demands. The languages that name a node put the
 * server in front of it; the HTTP snippet starts its request line with it.
 *
 * A placeholder is filled with the example the document states for it, which is
 * the name of an index a reader of the tutorials already has. A parameter that
 * states none falls back to its own name, so the path reads as a path rather
 * than as one with a hole in it.
 */
function targetFor(operation) {
	const path = operation.parameters
		.filter(parameter => parameter.in === 'path')
		.reduce(
			(url, parameter) => url.replaceAll(`{${parameter.name}}`, valueOf(parameter)),
			operation.path
		);

	const query = operation.parameters
		.filter(parameter => parameter.in === 'query' && parameter.required)
		.map(parameter => `${parameter.name}=${encodeURIComponent(valueOf(parameter))}`);

	return `${path}${query.length > 0 ? `?${query.join('&')}` : ''}`;
}

/** What a parameter carries in the call. */
function valueOf(parameter) {
	return String(parameter.example ?? parameter.schema?.default ?? parameter.name);
}

/** The headers every call to an endpoint carries. */
function headersFor(operation, hasBody) {
	const headers = [];

	if(operation.secured) headers.push(['Authorization', 'Bearer <key>']);
	if(hasBody) headers.push(['Content-Type', 'application/json']);

	for(const parameter of operation.parameters) {
		if(parameter.in === 'header' && parameter.required) {
			headers.push([parameter.name, valueOf(parameter)]);
		}
	}

	return headers;
}

/**
 * The key as each language reads it from the environment.
 *
 * The header is built with a placeholder rather than with the expression, so
 * that a language whose expression is not a string - Java concatenates, Go does
 * too - is not left quoting one.
 */
function withKey(value, expression) {
	return value.replace('<key>', expression);
}

/*
 * The request as it goes over the wire, minus the parts a reader gains nothing
 * from: the protocol version and the `Host` header. What is left is the method,
 * the path, the headers and the body, which is the same shape the front page
 * states a call in.
 *
 * The body is set with two spaces because it is read rather than run. The other
 * four snippets are pasted into a file that has its own indentation, and a tab
 * takes whatever width that file gives it.
 */
function http({ method, target, headers, body }) {
	const lines = [
		`${method} ${target}`,
		...headers.map(([name, value]) => `${name}: ${value}`)
	];

	if(body !== null) lines.push('', body.replaceAll('\t', '  '));

	return lines.join('\n');
}

function curl({ method, url, headers, body }) {
	const lines = [`curl -X ${method} "${url}"`];

	for(const [name, value] of headers) {
		lines.push(`  -H "${name}: ${withKey(value, `$${KEY_VARIABLE}`)}"`);
	}

	/*
	 * A single quote inside the body would end the argument, so each one is
	 * closed, escaped and reopened - the one spelling that works in every
	 * shell. No example in the document holds one today, and a body that does
	 * would otherwise be published as a call that fails to parse.
	 */
	if(body !== null) lines.push(`  -d '${body.replaceAll("'", `'\\''`)}'`);

	return lines.join(' \\\n');
}

function javascript({ method, url, headers, body }) {
	const entries = headers
		.map(([name, value]) => `\t\t"${name}": ${quoted(withKey(value, '${apiKey}'))}`)
		.join(',\n');

	const options = [
		`\tmethod: "${method}"`,
		...headers.length > 0 ? [`\theaders: {\n${entries}\n\t}`] : [],
		...body !== null ? [`\tbody: JSON.stringify(${indented(body, '\t')})`] : []
	];

	return [
		`const apiKey = process.env.${KEY_VARIABLE};`,
		'',
		`const response = await fetch("${url}", {`,
		options.join(',\n'),
		'});',
		'',
		'const result = await response.json();'
	].join('\n');
}

/** A JavaScript string, as a template literal where it interpolates the key. */
function quoted(value) {
	return value.includes('${') ? `\`${value}\`` : `"${value}"`;
}

function java({ method, url, headers, body }) {
	const lines = [
		`String apiKey = System.getenv("${KEY_VARIABLE}");`,
		''
	];

	/*
	 * A text block strips the indentation its closing delimiter stands at, so
	 * the body is written one level in and closed one level in. That is what
	 * keeps the JSON reading as JSON here and arriving at the node without the
	 * indentation of the Java file around it.
	 */
	if(body !== null) {
		lines.push('String body = """', shifted(body, '\t'), '\t""";', '');
	}

	lines.push(
		'HttpRequest request = HttpRequest.newBuilder()',
		`\t.uri(URI.create("${url}"))`,
		...headers.map(([name, value]) => `\t.header("${name}", ${javaValue(value)})`),
		`\t.${method}(${body === null ? '' : 'HttpRequest.BodyPublishers.ofString(body)'})`,
		'\t.build();',
		'',
		'HttpResponse<String> response = HttpClient.newHttpClient()',
		'\t.send(request, HttpResponse.BodyHandlers.ofString());'
	);

	return lines.join('\n');
}

/** A Java expression for a header value, concatenating the key where it holds one. */
function javaValue(value) {
	const [before, after] = value.split('<key>');
	if(after === undefined) return `"${value}"`;

	return [
		...before ? [`"${before}"`] : [],
		'apiKey',
		...after ? [`"${after}"`] : []
	].join(' + ');
}

function go({ method, url, headers, body }) {
	const lines = [`apiKey := os.Getenv("${KEY_VARIABLE}")`, ''];

	/*
	 * A raw string holds the body, because JSON is quotes all the way down and
	 * an interpreted string would be a backslash before every one of them. The
	 * one character a raw string cannot hold is a backtick, and JSON escapes
	 * none, so the body is written as it is read.
	 */
	if(body !== null) {
		lines.push(`body := strings.NewReader(\`${body}\`)`, '');
	}

	lines.push(
		`request, err := http.NewRequest("${method}", "${url}", ${body === null ? 'nil' : 'body'})`,
		'if err != nil {',
		'\tlog.Fatal(err)',
		'}',
		''
	);

	for(const [name, value] of headers) {
		lines.push(`request.Header.Set("${name}", ${goValue(value)})`);
	}

	lines.push('', 'response, err := http.DefaultClient.Do(request)');

	return lines.join('\n');
}

/** A Go expression for a header value, concatenating the key where it holds one. */
function goValue(value) {
	const [before, after] = value.split('<key>');
	if(after === undefined) return `"${value}"`;

	return [
		...before ? [`"${before}"`] : [],
		'apiKey',
		...after ? [`"${after}"`] : []
	].join('+');
}

/**
 * A block of JSON moved in by one level, so that it sits inside the call. The
 * first line keeps its place, because it follows whatever opened the call.
 */
function indented(body, indent) {
	return body.split('\n').map((line, index) => index === 0 ? line : `${indent}${line}`).join('\n');
}

/** A block of JSON moved in by one level, first line and all. */
function shifted(body, indent) {
	return body.split('\n').map(line => `${indent}${line}`).join('\n');
}
