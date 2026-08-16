package se.l4.exofind.engine.errors;

import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.ListIterable;

/**
 * Version of {@link Location} used to merge several locations into a single
 * one. This is used when types are modified or merged.
 */
public class MergedLocation implements Location {
	private final ImmutableList<Location> locations;

	public MergedLocation(
		ImmutableList<Location> locations
	) {
		this.locations = locations;
	}

	@Override
	public String describe() {
		var builder = new StringBuilder();

		for(var loc : list()) {
			if(builder.isEmpty()) {
				builder.append(loc.describe());
			} else {
				builder
					.append(" modified by ")
					.append(loc.describe());
			}
		}

		return builder.toString();
	}

	/**
	 * Get all the locations that have been merged together.
	 *
	 * @return
	 */
	public ListIterable<Location> list() {
		return locations;
	}

	/**
	 * Merge this location with another one. Used when things are modified
	 * dynamically.
	 *
	 * @param other
	 * @return
	 */
	@Override
	public MergedLocation mergeWith(Location other) {
		return new MergedLocation(locations.newWith(other));
	}

	/**
	 * Turn a location into a list of locations.
	 *
	 * @param location
	 * @return
	 */
	public static ListIterable<Location> toList(Location location) {
		return location instanceof MergedLocation m
			? m.list()
			: Lists.immutable.of(location);
	}

	/**
	 * Get a merged version of all the given locations.
	 *
	 * @param locations
	 * @return
	 */
	public static MergedLocation of(Location... locations) {
		return new MergedLocation(Lists.immutable.of(locations));
	}
}
