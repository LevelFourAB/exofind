package se.l4.exofind.engine.api.errors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import se.l4.exofind.engine.api.ApiEndpoints;

/**
 * The error codes each operation of the API can answer with, read out of the
 * compiled classes.
 *
 * <p>A code is declared once, as {@code ErrorType.withCode(...)} held in a
 * static field, and returned where a method reads that field. Walking from an
 * endpoint over the methods it calls therefore says which codes it can reach,
 * and {@code ErrorCodeCoverageTest} holds the endpoint to naming them.
 *
 * <h2>Where the walk stops</h2>
 *
 * <p>The walk follows calls into {@code se.l4.exofind.engine.api}, which holds
 * the resource and the mappers it hands the request to, and into the
 * constructor of any exception it arrives at. It does not follow a call into
 * the engine itself. A search reaches the whole of indexing and analysis, and
 * those codes report a stored index that disagrees with itself, which no caller
 * can correct. Name such a code by hand on the endpoints that return it.
 *
 * <p>A call on a type is answered by every implementation below that type,
 * because which one runs is decided while the request does. A constructor and a
 * static call have one target, so the walk does not widen those. Without the
 * distinction, {@code new ValidationException(...)} would be taken to reach
 * every exception with a constructor of the same shape.
 */
final class ApiErrorCodes {
	/** The class holding every code, as the class file spells it. */
	private static final String ERROR_TYPE = "se/l4/exofind/engine/errors/ErrorType";

	/** The type of the fields the codes are held in, as a descriptor. */
	private static final String ERROR_TYPE_FIELD = "L" + ERROR_TYPE + ";";

	/** The compiled classes of this project, which is all the walk reads. */
	private static final String PROJECT = "se/l4/exofind/";

	/** The part of it the walk follows calls into. */
	private static final String API = "se/l4/exofind/engine/api/";

	/** How a call made on a type, which any subtype may answer, is marked. */
	private static final char VIRTUAL = 'V';

	/** How a call with exactly one target is marked. */
	private static final char DIRECT = 'S';

	/** The code held by a static field, keyed by owner and field name. */
	private final Map<String, String> codeOfField = new HashMap<>();

	/** The fields of codes one method reads, keyed by the method. */
	private final Map<String, Set<String>> readsOf = new HashMap<>();

	/** The methods one method calls, keyed by the method. */
	private final Map<String, Set<String>> callsOf = new HashMap<>();

	/** The superclass of one class, for finding an inherited declaration. */
	private final Map<String, String> superOf = new HashMap<>();

	/** The classes directly below one class or interface. */
	private final Map<String, Set<String>> belowOf = new HashMap<>();

	/** The methods one class declares itself, as name and descriptor. */
	private final Map<String, Set<String>> declaresOf = new HashMap<>();

	/** Whether a class is a {@link Throwable}, which the walk asks repeatedly. */
	private final Map<String, Boolean> throwable = new HashMap<>();

	/** Every class below one class or interface, which the walk asks repeatedly. */
	private final Map<String, Set<String>> below = new HashMap<>();

	private ApiErrorCodes() {
	}

	/**
	 * The codes each operation of the API can answer with, keyed the way
	 * {@link ApiEndpoints#key(java.lang.reflect.Method)} keys one.
	 *
	 * <p>Two resource methods can serve one operation, which is how an endpoint
	 * that reads a second media type is written. Such an endpoint answers with
	 * one set of codes, so the key is the operation and not the method.
	 */
	static Map<String, Set<String>> byOperation() throws Exception {
		var walk = new ApiErrorCodes();
		walk.read(classes());

		var codes = new TreeMap<String, Set<String>>();

		for(var endpoint : ApiEndpoints.endpoints()) {
			var start = Type.getInternalName(endpoint.getDeclaringClass())
				+ "#" + endpoint.getName() + Type.getMethodDescriptor(endpoint);

			codes.computeIfAbsent(ApiEndpoints.key(endpoint), key -> new TreeSet<>())
				.addAll(walk.reachableFrom(start));
		}

		return codes;
	}

	/** The codes one method can reach, over everything it calls. */
	private Set<String> reachableFrom(String start) {
		var seen = new HashSet<String>();
		var queue = new ArrayDeque<String>();
		var codes = new TreeSet<String>();

		seen.add(start);
		queue.add(start);

		while(!queue.isEmpty()) {
			var method = queue.poll();

			for(var field : readsOf.getOrDefault(method, Set.of())) {
				var code = codeOfField.get(field);
				if(code != null) {
					codes.add(code);
				}
			}

			for(var call : callsOf.getOrDefault(method, Set.of())) {
				for(var target : targetsOf(call)) {
					if(seen.add(target)) {
						queue.add(target);
					}
				}
			}
		}

		return codes;
	}

	/**
	 * The methods one call can end up in, or nothing when the call leaves the
	 * part of the project the walk follows.
	 */
	private List<String> targetsOf(String call) {
		var virtual = call.charAt(0) == VIRTUAL;
		var owner = call.substring(1, call.indexOf('#'));
		var signature = call.substring(call.indexOf('#') + 1);

		var followed = owner.startsWith(API)
			|| (signature.startsWith("<init>") && isThrowable(owner));

		if(!followed) {
			return List.of();
		}

		var targets = new ArrayList<String>();

		// The declaration the call names, which a subclass can inherit
		for(var at = owner; at != null; at = superOf.get(at)) {
			if(declaresOf.getOrDefault(at, Set.of()).contains(signature)) {
				targets.add(at + "#" + signature);
				break;
			}
		}

		if(virtual) {
			for(var type : below(owner)) {
				if(declaresOf.getOrDefault(type, Set.of()).contains(signature)) {
					targets.add(type + "#" + signature);
				}
			}
		}

		return targets;
	}

	/** Every class of this project below a class or interface. */
	private Set<String> below(String type) {
		return below.computeIfAbsent(type, start -> {
			var found = new HashSet<String>();
			var queue = new ArrayDeque<String>();
			queue.add(start);

			while(!queue.isEmpty()) {
				for(var type2 : belowOf.getOrDefault(queue.poll(), Set.of())) {
					if(found.add(type2)) {
						queue.add(type2);
					}
				}
			}

			return found;
		});
	}

	/**
	 * Whether a class is a {@link Throwable}. Asked of the loaded class rather
	 * than of the walked hierarchy, which holds only the classes of this
	 * project and so stops below {@code RuntimeException}.
	 */
	private boolean isThrowable(String name) {
		return throwable.computeIfAbsent(name, internal -> {
			try {
				return Throwable.class.isAssignableFrom(
					Class.forName(
						internal.replace('/', '.'),
						false,
						ApiErrorCodes.class.getClassLoader()
					)
				);
			} catch(Throwable e) {
				// Something the walk cannot load is not followed into
				return false;
			}
		});
	}

	private void read(List<Path> classes) throws IOException {
		for(var file : classes) {
			new ClassReader(Files.readAllBytes(file))
				.accept(new Reader(), ClassReader.SKIP_FRAMES);
		}
	}

	/** Reads one class into the maps above. */
	private final class Reader extends ClassVisitor {
		private String name;

		Reader() {
			super(Opcodes.ASM9);
		}

		@Override
		public void visit(
			int version,
			int access,
			String name,
			String signature,
			String superName,
			String[] interfaces
		) {
			this.name = name;

			if(superName != null) {
				superOf.put(name, superName);
				belowOf.computeIfAbsent(superName, type -> new HashSet<>()).add(name);
			}

			if(interfaces != null) {
				for(var type : interfaces) {
					belowOf.computeIfAbsent(type, t -> new HashSet<>()).add(name);
				}
			}
		}

		@Override
		public MethodVisitor visitMethod(
			int access,
			String method,
			String descriptor,
			String signature,
			String[] exceptions
		) {
			declaresOf.computeIfAbsent(name, type -> new HashSet<>())
				.add(method + descriptor);

			return new Body(name + "#" + method + descriptor);
		}
	}

	/** Reads one method body into the maps above. */
	private final class Body extends MethodVisitor {
		private final String method;

		/** The last string loaded, which is the code of a `withCode` that follows. */
		private String loaded;

		/** The code of the field being assigned, once `withCode` has been called. */
		private String declared;

		Body(String method) {
			super(Opcodes.ASM9);

			this.method = method;
		}

		@Override
		public void visitLdcInsn(Object value) {
			if(value instanceof String text) {
				loaded = text;
			}
		}

		@Override
		public void visitMethodInsn(
			int opcode,
			String owner,
			String name,
			String descriptor,
			boolean isInterface
		) {
			if(owner.equals(ERROR_TYPE) && name.equals("withCode")) {
				declared = loaded;
			}

			if(owner.startsWith(PROJECT)) {
				var mark = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE
					? VIRTUAL
					: DIRECT;

				callsOf.computeIfAbsent(method, m -> new HashSet<>())
					.add(mark + owner + "#" + name + descriptor);
			}
		}

		@Override
		public void visitInvokeDynamicInsn(
			String name,
			String descriptor,
			Handle bootstrap,
			Object... arguments
		) {
			/*
			 * A lambda body compiles to a method of its own that nothing calls
			 * directly; only the handle in the bootstrap arguments names it.
			 * Without this the walk stops at every lambda, and most of the
			 * validation of a request is written as one.
			 */
			for(var argument : arguments) {
				if(argument instanceof Handle handle && handle.getOwner().startsWith(PROJECT)) {
					callsOf.computeIfAbsent(method, m -> new HashSet<>())
						.add(DIRECT + handle.getOwner() + "#" + handle.getName() + handle.getDesc());
				}
			}
		}

		@Override
		public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
			if(!descriptor.equals(ERROR_TYPE_FIELD)) {
				return;
			}

			if(opcode == Opcodes.PUTSTATIC && declared != null) {
				codeOfField.put(owner + "." + name, declared);
				declared = null;
			} else if(opcode == Opcodes.GETSTATIC) {
				readsOf.computeIfAbsent(method, m -> new HashSet<>()).add(owner + "." + name);
			}
		}
	}

	/**
	 * Every compiled class of this project, read from where these classes were
	 * compiled to. Answers while a build runs, which is the only time anything
	 * asks.
	 */
	private static List<Path> classes() throws Exception {
		var source = ApiErrorCodes.class.getProtectionDomain().getCodeSource();

		if(source == null || source.getLocation() == null) {
			throw new IllegalStateException(
				"The test classes report no location, so the compiled classes could not be found"
			);
		}

		var root = Path.of(source.getLocation().toURI()).resolveSibling("classes")
			.resolve(PROJECT);

		if(!Files.isDirectory(root)) {
			throw new IllegalStateException(
				"No compiled classes under " + root + ", so no error code could be found"
			);
		}

		try(Stream<Path> files = Files.walk(root)) {
			return files.filter(file -> file.toString().endsWith(".class")).toList();
		}
	}
}
