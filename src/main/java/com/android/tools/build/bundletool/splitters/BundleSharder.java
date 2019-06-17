/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.splitters;

import static com.android.tools.build.bundletool.model.utils.TargetingProtoUtils.abiUniverse;
import static com.android.tools.build.bundletool.model.utils.TargetingProtoUtils.densityUniverse;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.android.bundle.Devices.DeviceSpec;
import com.android.bundle.Targeting.ApkTargeting;
import com.android.tools.build.bundletool.device.ApkMatcher;
import com.android.tools.build.bundletool.mergers.D8DexMerger;
import com.android.tools.build.bundletool.mergers.ModuleSplitsToShardMerger;
import com.android.tools.build.bundletool.mergers.SameTargetingMerger;
import com.android.tools.build.bundletool.model.BundleMetadata;
import com.android.tools.build.bundletool.model.BundleModule;
import com.android.tools.build.bundletool.model.ModuleSplit;
import com.android.tools.build.bundletool.model.OptimizationDimension;
import com.android.tools.build.bundletool.model.ShardedSystemSplits;
import com.android.tools.build.bundletool.model.exceptions.CommandExecutionException;
import com.android.tools.build.bundletool.model.version.Version;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Generator of sharded APKs compatible with pre-L devices.
 *
 * <p>Sharded APK is a standalone APK that has been optimized in certain dimensions by stripping out
 * unnecessary files. Sharding is supported only by ABI and screen density. When device spec is
 * present we merge only the languages supported into sharded APK.
 */
public class BundleSharder {

  private final Version bundleVersion;
  private final ModuleSplitsToShardMerger merger;
  private final BundleSharderConfiguration bundleSharderConfiguration;

  public BundleSharder(
      Path globalTempDir,
      Version bundleVersion,
      BundleSharderConfiguration bundleSharderConfiguration) {
    this.bundleVersion = bundleVersion;
    this.merger = new ModuleSplitsToShardMerger(new D8DexMerger(), globalTempDir);
    this.bundleSharderConfiguration = bundleSharderConfiguration;
  }

  /**
   * Generates sharded APKs from the input modules.
   *
   * <p>Each shard targets a specific point in the "ABI" x "Screen Density" configuration space. To
   * generate shards from bundle modules, we generate module splits from each of the given modules
   * and then partition the splits by their targeting into groups of:
   *
   * <ol>
   *   <li>Master splits (have no ABI or density targeting)
   *   <li>ABI splits
   *   <li>Screen density splits
   * </ol>
   *
   * <p>A concrete sharded APK for configuration ("abi=X", "density=Y") is generated by fusing:
   *
   * <ul>
   *   <li>All master splits - these are unconditionally contained within each sharded APK
   *   <li>ABI splits whose targeting is "abi=X"
   *   <li>Density splits whose targeting is "density=Y"
   * </ul>
   */
  public ImmutableList<ModuleSplit> shardBundle(
      ImmutableList<BundleModule> modules,
      ImmutableSet<OptimizationDimension> shardingDimensions,
      BundleMetadata bundleMetadata) {
    checkState(
        !bundleSharderConfiguration.getDeviceSpec().isPresent(),
        "Device spec should be set only when sharding for system apps.");
    return merger.merge(generateUnfusedShards(modules, shardingDimensions), bundleMetadata);
  }

  /**
   * Generates sharded system APK and additional split APK from the given modules.
   *
   * <p>We target the (ABI, Screen Density, Languages) configuration specified in the device spec.
   */
  public ShardedSystemSplits shardForSystemApps(
      ImmutableList<BundleModule> modules,
      ImmutableSet<OptimizationDimension> shardingDimensions,
      BundleMetadata bundleMetadata) {
    checkState(
        bundleSharderConfiguration.getDeviceSpec().isPresent(),
        "Device spec should be set when sharding for system apps.");
    return merger.mergeSystemShard(
        Iterables.getOnlyElement(generateUnfusedShards(modules, shardingDimensions)),
        modules.stream()
            .filter(BundleModule::isIncludedInFusing)
            .map(BundleModule::getName)
            .collect(toImmutableSet()),
        bundleMetadata,
        bundleSharderConfiguration.getDeviceSpec().get());
  }

  private ImmutableList<ImmutableList<ModuleSplit>> generateUnfusedShards(
      ImmutableList<BundleModule> modules, ImmutableSet<OptimizationDimension> shardingDimensions) {
    checkArgument(!modules.isEmpty(), "At least one module is required.");

    // Generate a flat list of splits from all input modules.
    ImmutableList<ModuleSplit> moduleSplits =
        modules.stream()
            .flatMap(module -> generateSplits(module, shardingDimensions).stream())
            .collect(toImmutableList());

    // Each sublist below represents a collection of splits targeting a specific device
    // configuration.
    return groupSplitsToShards(moduleSplits);
  }

  /**
   * Generates sharded APKs from APEX module. Each sharded APK is generated by fusing one master
   * split and one system image file, targeted by multi Abi.
   */
  public ImmutableList<ModuleSplit> shardApexBundle(BundleModule apexModule) {
    ImmutableList<ModuleSplit> splits = generateSplits(apexModule, ImmutableSet.of());
    ImmutableList<ImmutableList<ModuleSplit>> unfusedShards = groupSplitsToShardsForApex(splits);
    return merger.mergeApex(unfusedShards);
  }

  private ImmutableList<ModuleSplit> generateSplits(
      BundleModule module, ImmutableSet<OptimizationDimension> shardingDimensions) {
    ImmutableList.Builder<ModuleSplit> rawSplits = ImmutableList.builder();

    // Native libraries splits.
    SplittingPipeline nativePipeline = createNativeLibrariesSplittingPipeline(shardingDimensions);
    rawSplits.addAll(nativePipeline.split(ModuleSplit.forNativeLibraries(module)));

    // Resources splits.
    SplittingPipeline resourcesPipeline = createResourcesSplittingPipeline(shardingDimensions);
    rawSplits.addAll(resourcesPipeline.split(ModuleSplit.forResources(module)));

    // Apex images splits.
    SplittingPipeline apexPipeline = createApexImagesSplittingPipeline();
    rawSplits.addAll(apexPipeline.split(ModuleSplit.forApex(module)));

    // Assets splits.
    SplittingPipeline assetsPipeline = createAssetsSplittingPipeline(shardingDimensions);
    rawSplits.addAll(assetsPipeline.split(ModuleSplit.forAssets(module)));

    // Other files.
    rawSplits.add(ModuleSplit.forDex(module));
    rawSplits.add(ModuleSplit.forRoot(module));

    // Merge splits with the same targeting and make a single master split.
    ImmutableList<ModuleSplit> mergedSplits = new SameTargetingMerger().merge(rawSplits.build());

    // Remove the splitName from any standalone apks, as these are only used for instant apps (L+).
    mergedSplits =
        mergedSplits.stream().map(ModuleSplit::removeSplitName).collect(toImmutableList());

    // Check that we have only one master split.
    long masterSplitCount = mergedSplits.stream().filter(ModuleSplit::isMasterSplit).count();
    checkState(masterSplitCount == 1, "Expected one master split, got %s.", masterSplitCount);

    return mergedSplits;
  }

  private SplittingPipeline createNativeLibrariesSplittingPipeline(
      ImmutableSet<OptimizationDimension> shardingDimensions) {
    return new SplittingPipeline(
        shardingDimensions.contains(OptimizationDimension.ABI)
            ? ImmutableList.of(
                new AbiNativeLibrariesSplitter(bundleSharderConfiguration.getGenerate64BitShard()))
            : ImmutableList.of());
  }

  private SplittingPipeline createResourcesSplittingPipeline(
      ImmutableSet<OptimizationDimension> shardingDimensions) {
    ImmutableList.Builder<ModuleSplitSplitter> resourceSplitters = ImmutableList.builder();

    if (shardingDimensions.contains(OptimizationDimension.SCREEN_DENSITY)) {
      resourceSplitters.add(
          new ScreenDensityResourcesSplitter(
              bundleVersion,
              /* pinWholeResourceToMaster= */ Predicates.alwaysFalse(),
              /* pinLowestBucketOfResourceToMaster= */ Predicates.alwaysFalse()));
    }

    if (shardingDimensions.contains(OptimizationDimension.LANGUAGE)
        && bundleSharderConfiguration.splitByLanguage()) {
      resourceSplitters.add(new LanguageResourcesSplitter());
    }

    return new SplittingPipeline(resourceSplitters.build());
  }

  private SplittingPipeline createAssetsSplittingPipeline(
      ImmutableSet<OptimizationDimension> shardingDimensions) {
    ImmutableList.Builder<ModuleSplitSplitter> assetsSplitters = ImmutableList.builder();
    if (shardingDimensions.contains(OptimizationDimension.LANGUAGE)
        && bundleSharderConfiguration.splitByLanguage()) {
      assetsSplitters.add(LanguageAssetsSplitter.create());
    }
    return new SplittingPipeline(assetsSplitters.build());
  }

  private SplittingPipeline createApexImagesSplittingPipeline() {
    // We always split APEX image files by MultiAbi, regardless of OptimizationDimension.
    return new SplittingPipeline(ImmutableList.of(new AbiApexImagesSplitter()));
  }

  private ImmutableList<ImmutableList<ModuleSplit>> groupSplitsToShards(
      ImmutableList<ModuleSplit> splits) {
    // The input contains master split and possibly ABI and/or density splits for module m1, m2 etc.
    // Let's denote the splits as follows:
    //   * for module 1: {m1-master, m1-abi1, m1-abi2, ..., m1-density1, m1-density2, ...}
    //   * for module 2: {m2-master, m2-abi1, m2-abi2, ..., m2-density1, m2-density2, ...}
    //   * etc.
    //
    // First we partition the splits by their targeting dimension:
    //   * master splits:  {m1-master, m2-master, ...}
    //   * ABI splits:     {m1-abi1, m1-abi2, ..., m2-abi1, m2-abi2, ...}
    //   * density splits: {m1-density1, m1-density2, ..., m2-density1, m2-density2, ...}
    ImmutableSet<ModuleSplit> abiSplits =
        subsetWithTargeting(splits, ApkTargeting::hasAbiTargeting);
    ImmutableSet<ModuleSplit> densitySplits =
        subsetWithTargeting(splits, ApkTargeting::hasScreenDensityTargeting);

    ImmutableSet<ModuleSplit> languageSplits = getLanguageSplits(splits);
    ImmutableSet<ModuleSplit> masterSplits = getMasterSplits(splits);

    checkState(
        Sets.intersection(Sets.newHashSet(abiSplits), Sets.newHashSet(densitySplits)).isEmpty(),
        "No split is expected to have both ABI and screen density targeting.");
    // Density splitter is expected to produce the same density universe for any module.
    checkState(
        sameTargetedUniverse(densitySplits, split -> densityUniverse(split.getApkTargeting())),
        "Density splits are expected to cover the same densities.");
    if (!sameTargetedUniverse(abiSplits, split -> abiUniverse(split.getApkTargeting()))) {
      throw CommandExecutionException.builder()
          .withMessage(
              "Modules for standalone APKs must cover the same ABIs when optimizing for ABI.")
          .build();
    }

    // Next, within each of the groups perform additional partitioning based on the actual value in
    // the targeting dimension:
    //   * master splits: { {m1-master, m2-master, ...} }
    //   * abi splits: {
    //                   {m1-abi1, m2-abi1, ...},  // targeting abi1
    //                   {m1-abi2, m2-abi2, ...},  // targeting abi2
    //                   ...
    //                 }
    //   * density splits: {
    //                       {m1-density1, m2-density1, ...},  // targeting density1
    //                       {m1-density2, m2-density2, ...},  // targeting density2
    //                       ...
    //                     }
    // Note that if any of the partitioning was empty, we use {{}} instead.
    Collection<Collection<ModuleSplit>> abiSplitsSubsets =
        nonEmpty(partitionByTargeting(abiSplits));
    Collection<Collection<ModuleSplit>> densitySplitsSubsets =
        nonEmpty(partitionByTargeting(densitySplits));

    // Finally each member of cartesian product "master splits" x "abi splits" x "density splits"
    // represents a collection of splits that need to be fused in order to produce a single
    // sharded APK.
    ImmutableList.Builder<ImmutableList<ModuleSplit>> shards = ImmutableList.builder();
    for (Collection<ModuleSplit> abiSplitsSubset : abiSplitsSubsets) {
      for (Collection<ModuleSplit> densitySplitsSubset : densitySplitsSubsets) {
        // Describe a future shard as a collection of splits that need to be fused.
        shards.add(
            ImmutableList.<ModuleSplit>builder()
                .addAll(masterSplits)
                .addAll(languageSplits)
                .addAll(abiSplitsSubset)
                .addAll(densitySplitsSubset)
                .build());
      }
    }

    return shards.build();
  }

  private ImmutableList<ImmutableList<ModuleSplit>> groupSplitsToShardsForApex(
      ImmutableList<ModuleSplit> splits) {
    Set<ModuleSplit> multiAbiSplits =
        subsetWithTargeting(splits, ApkTargeting::hasMultiAbiTargeting);
    Set<ModuleSplit> masterSplits = Sets.difference(ImmutableSet.copyOf(splits), multiAbiSplits);

    ModuleSplit masterSplit = Iterables.getOnlyElement(masterSplits);
    checkState(
        masterSplit.getApkTargeting().equals(ApkTargeting.getDefaultInstance()),
        "Master splits are expected to have default targeting.");

    return multiAbiSplits.stream()
        .map(abiSplit -> ImmutableList.of(masterSplit, abiSplit))
        .collect(toImmutableList());
  }

  private ImmutableSet<ModuleSplit> subsetWithTargeting(
      ImmutableList<ModuleSplit> splits, Predicate<ApkTargeting> predicate) {
    return splits.stream()
        .filter(split -> predicate.test(split.getApkTargeting()))
        .filter(
            split ->
                bundleSharderConfiguration
                    .getDeviceSpec()
                    .map(spec -> splitMatchesDeviceSpec(split, spec))
                    .orElse(true))
        .collect(toImmutableSet());
  }

  /** Returns master splits, i.e splits without any targeting. */
  private ImmutableSet<ModuleSplit> getMasterSplits(ImmutableList<ModuleSplit> splits) {
    ImmutableSet<ModuleSplit> masterSplits =
        splits.stream().filter(ModuleSplit::isMasterSplit).collect(toImmutableSet());

    checkState(
        masterSplits.size() >= 1,
        "Expecting at least one master split, got %s.",
        masterSplits.size());
    checkState(
        masterSplits.stream()
            .allMatch(split -> split.getApkTargeting().equals(ApkTargeting.getDefaultInstance())),
        "Master splits are expected to have default targeting.");

    return masterSplits;
  }

  private static ImmutableSet<ModuleSplit> getLanguageSplits(ImmutableList<ModuleSplit> splits) {
    return splits.stream()
        .filter(split -> split.getApkTargeting().hasLanguageTargeting())
        .collect(toImmutableSet());
  }

  private static Collection<Collection<ModuleSplit>> partitionByTargeting(
      Collection<ModuleSplit> splits) {
    return Multimaps.index(splits, ModuleSplit::getApkTargeting).asMap().values();
  }

  private static <T> Collection<Collection<T>> nonEmpty(Collection<Collection<T>> x) {
    return x.isEmpty() ? ImmutableList.of(ImmutableList.of()) : x;
  }

  private static boolean sameTargetedUniverse(
      Set<ModuleSplit> splits, Function<ModuleSplit, Collection<?>> getUniverseFn) {
    long distinctNonEmptyUniverseCount =
        splits.stream()
            .map(getUniverseFn::apply)
            // Filter out splits having no targeting in the dimension of the universe.
            .filter(not(Collection::isEmpty))
            .distinct()
            .count();
    return distinctNonEmptyUniverseCount <= 1;
  }

  static boolean splitMatchesDeviceSpec(ModuleSplit moduleSplit, DeviceSpec deviceSpec) {
    return new ApkMatcher(deviceSpec).matchesModuleSplitByTargeting(moduleSplit);
  }
}
