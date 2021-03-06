/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.git;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.copybara.exception.ValidationException.checkCondition;
import static com.google.copybara.git.gerritapi.IncludeResult.DETAILED_ACCOUNTS;
import static com.google.copybara.git.gerritapi.IncludeResult.DETAILED_LABELS;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.copybara.BaselinesWithoutLabelVisitor;
import com.google.copybara.Endpoint;
import com.google.copybara.GeneralOptions;
import com.google.copybara.Options;
import com.google.copybara.Origin;
import com.google.copybara.authoring.Authoring;
import com.google.copybara.checks.Checker;
import com.google.copybara.exception.CannotResolveRevisionException;
import com.google.copybara.exception.EmptyChangeException;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.git.gerritapi.AccountInfo;
import com.google.copybara.git.gerritapi.ChangeInfo;
import com.google.copybara.git.gerritapi.GerritApi;
import com.google.copybara.git.gerritapi.GetChangeInput;
import com.google.copybara.transform.patch.PatchTransformation;
import com.google.copybara.util.Glob;
import com.google.copybara.util.console.Console;
import java.util.List;
import java.util.Map.Entry;
import javax.annotation.Nullable;

/**
 * A {@link Origin} that can read Gerrit reviews TODO(malcon): Implement Reader/getChanges to detect
 * already migrated patchets
 */
public class GerritOrigin extends GitOrigin {

  private final GeneralOptions generalOptions;
  private final GitOptions gitOptions;
  private final GitOriginOptions gitOriginOptions;
  private final GerritOptions gerritOptions;
  private final SubmoduleStrategy submoduleStrategy;
  private final boolean includeBranchCommitLogs;
  @Nullable private final Checker endpointChecker;
  @Nullable private final PatchTransformation patchTransformation;
  @Nullable private final String branch;

  private GerritOrigin(
      GeneralOptions generalOptions,
      String repoUrl,
      @Nullable String configRef,
      GitOptions gitOptions,
      GitOriginOptions gitOriginOptions,
      GerritOptions gerritOptions,
      SubmoduleStrategy submoduleStrategy,
      boolean includeBranchCommitLogs,
      boolean firstParent,
      @Nullable Checker endpointChecker,
      @Nullable PatchTransformation patchTransformation,
      @Nullable String branch,
      boolean describeVersion) {
    super(
        generalOptions,
        repoUrl,
        configRef,
        GitRepoType.GERRIT,
        gitOptions,
        gitOriginOptions,
        submoduleStrategy,
        includeBranchCommitLogs,
        firstParent,
        patchTransformation, describeVersion,
        /*versionSelector=*/null);
    this.generalOptions = checkNotNull(generalOptions);
    this.gitOptions = checkNotNull(gitOptions);
    this.gitOriginOptions = checkNotNull(gitOriginOptions);
    this.gerritOptions = checkNotNull(gerritOptions);
    this.submoduleStrategy = checkNotNull(submoduleStrategy);
    this.includeBranchCommitLogs = includeBranchCommitLogs;
    this.endpointChecker = endpointChecker;
    this.patchTransformation = patchTransformation;
    this.branch = branch;
  }

  @Override
  public GitRevision resolve(@Nullable String reference) throws RepoException, ValidationException {
    generalOptions.console().progress("Git Origin: Initializing local repo");

    checkCondition(!Strings.isNullOrEmpty(reference), "Expecting a change number as reference");

    GerritChange change = GerritChange.resolve(getRepository(), repoUrl, reference,
        this.generalOptions);
    if (change == null) {
      GitRevision gitRevision = GitRepoType.GIT.resolveRef(getRepository(), repoUrl, reference,
          this.generalOptions, describeVersion);
      return describeVersion ? getRepository().addDescribeVersion(gitRevision) : gitRevision;
    }
    GerritApi api = gerritOptions.newGerritApi(repoUrl);

    ChangeInfo response = api.getChange(Integer.toString(change.getChange()),
        new GetChangeInput(ImmutableSet.of(DETAILED_ACCOUNTS, DETAILED_LABELS)));

    if (branch != null && !branch.equals(response.getBranch())) {
      throw new EmptyChangeException(String.format(
          "Skipping import of change %s for branch %s. Only tracking changes for branch %s",
          change.getChange(), response.getBranch(), branch));
    }

    ImmutableMultimap.Builder<String, String> labels = ImmutableMultimap.builder();

    labels.put(GerritChange.GERRIT_CHANGE_BRANCH, response.getBranch());
    if (response.getTopic() != null) {
      labels.put(GerritChange.GERRIT_CHANGE_TOPIC, response.getTopic());
    }
    labels.put(GerritChange.GERRIT_COMPLETE_CHANGE_ID_LABEL, response.getId());
    for (Entry<String, List<AccountInfo>> e : response.getReviewers().entrySet()) {
      for (AccountInfo info : e.getValue()) {
        if (info.getEmail() != null) {
          labels.put("GERRIT_" + e.getKey() + "_EMAIL", info.getEmail());
        }
      }
    }

    if (response.getOwner().getEmail() != null) {
      labels.put(GerritChange.GERRIT_OWNER_EMAIL_LABEL, response.getOwner().getEmail());
    }

    GitRevision gitRevision = change.fetch(labels.build());
    return describeVersion ? getRepository().addDescribeVersion(gitRevision) : gitRevision;
  }

  /** Builds a new {@link GerritOrigin}. */
  static GerritOrigin newGerritOrigin(
      Options options, String url, SubmoduleStrategy submoduleStrategy, boolean firstParent,
      @Nullable Checker endpointChecker, @Nullable PatchTransformation patchTransformation,
      @Nullable String branch, boolean describeVersion) {

    return new GerritOrigin(
        options.get(GeneralOptions.class),
        url,
        /* configRef= */ null,
        options.get(GitOptions.class),
        options.get(GitOriginOptions.class),
        options.get(GerritOptions.class),
        submoduleStrategy,
        /*includeBranchCommitLogs=*/ false,
        firstParent,
        endpointChecker,
        patchTransformation,
        branch,
        describeVersion);
  }

  @Override
  public Reader<GitRevision> newReader(Glob originFiles, Authoring authoring) {
    return new GitOrigin.ReaderImpl(repoUrl, originFiles, authoring, gitOptions, gitOriginOptions,
        generalOptions, includeBranchCommitLogs, submoduleStrategy, firstParent,
        patchTransformation, describeVersion) {

      @Override
      public ImmutableList<GitRevision> findBaselinesWithoutLabel(
          GitRevision startRevision, int limit)
          throws RepoException, CannotResolveRevisionException {

        // Skip the first change as it is the Gerrit review change
        BaselinesWithoutLabelVisitor<GitRevision> visitor =
            new BaselinesWithoutLabelVisitor<>(originFiles, limit, /*skipFirst=*/ true);
        visitChanges(startRevision, visitor);
        return visitor.getResult();
      }

      @Override
      public Endpoint getFeedbackEndPoint(Console console) throws ValidationException {
        gerritOptions.validateEndpointChecker(endpointChecker, repoUrl);
        return new GerritEndpoint(
            gerritOptions.newGerritApiSupplier(repoUrl, endpointChecker), repoUrl, console);
      }
    };
  }
}
