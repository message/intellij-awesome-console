package awesome.console;

import awesome.console.config.AwesomeConsoleConfig;
import awesome.console.match.FileLinkMatch;
import awesome.console.match.URLLinkMatch;
import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.filters.HyperlinkInfoFactory;
import com.intellij.ide.browsers.OpenUrlHyperlinkInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AwesomeLinkFilter implements Filter {
	private static final Pattern FILE_PATTERN = Pattern.compile(
			"(?<link>(?<path>([.~])?(?:[a-zA-Z]:\\\\|/)?\\w[\\w/\\-.\\\\]*\\.[\\w\\-.]+)\\$?" +
			"(?:(?::|, line |:\\[|\\()(?<row>\\d+)(?:[:,]( column )?(?<col>\\d+)([)\\]])?)?)?)",
			Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern URL_PATTERN = Pattern.compile(
			"(?<link>[(']?(?<protocol>(([a-zA-Z]+):)?([/\\\\~]))(?<path>[-.!~*\\\\'()\\w;/?:@&=+$,%#]+))",
			Pattern.UNICODE_CHARACTER_CLASS);
	private static final int maxSearchDepth = 1;

	private final AwesomeConsoleConfig config;
	private final Map<String, List<VirtualFile>> fileCache;
	private final Map<String, List<VirtualFile>> fileBaseCache;
	private final Project project;
	private final List<String> srcRoots;
	private final Matcher fileMatcher;
	private final Matcher urlMatcher;
	private final ProjectRootManager projectRootManager;

	public AwesomeLinkFilter(final Project project) {
		this.project = project;
		this.fileCache = new HashMap<>();
		this.fileBaseCache = new HashMap<>();
		projectRootManager = ProjectRootManager.getInstance(project);
		srcRoots = getSourceRoots();
		config = AwesomeConsoleConfig.getInstance();
		fileMatcher = FILE_PATTERN.matcher("");
		urlMatcher = URL_PATTERN.matcher("");

		createFileCache();
	}

	@Nullable
	@Override
	public Result applyFilter(final String line, final int endPoint) {
		final List<ResultItem> results = new ArrayList<>();
		final int startPoint = endPoint - line.length();
		final List<String> chunks = splitLine(line);
		int offset = 0;

		for (final String chunk : chunks) {
			if (config.SEARCH_URLS) {
				results.addAll(getResultItemsUrl(chunk, startPoint + offset));
			}
			results.addAll(getResultItemsFile(chunk, startPoint + offset));
			offset += chunk.length();
		}

		return new Result(results);
	}

	public List<String> splitLine(final String line) {
		final List<String> chunks = new ArrayList<>();
		final int length = line.length();
		if (!config.LIMIT_LINE_LENGTH || config.LINE_MAX_LENGTH >= length) {
			chunks.add(line);
			return chunks;
		}
		if (!config.SPLIT_ON_LIMIT) {
			chunks.add(line.substring(0, config.LINE_MAX_LENGTH));
			return chunks;
		}
		int offset = 0;
		do {
			final String chunk = line.substring(offset, Math.min(length, offset + config.LINE_MAX_LENGTH));
			chunks.add(chunk);
			offset += config.LINE_MAX_LENGTH;
		} while (offset < length - 1);
		return chunks;
	}

	public List<ResultItem> getResultItemsUrl(final String line, final int startPoint) {
		final List<ResultItem> results = new ArrayList<>();
		final List<URLLinkMatch> matches = detectURLs(line);

		for (final URLLinkMatch match : matches) {
			final String file = getFileFromUrl(match.match);

			if (null != file && !new File(file).exists()) {
				continue;
			}
			results.add(
					new Result(
							startPoint + match.start,
							startPoint + match.end,
							new OpenUrlHyperlinkInfo(match.match))
			);
		}
		return results;
	}

	public String getFileFromUrl(final String url) {
		if (url.startsWith("/")) {
			return url;
		}
		final String fileUrl = "file://";
		if (url.startsWith(fileUrl)) {
			return url.substring(fileUrl.length());
		}
		return null;
	}

	public List<ResultItem> getResultItemsFile(final String line, final int startPoint) {
		final List<ResultItem> results = new ArrayList<>();
		final HyperlinkInfoFactory hyperlinkInfoFactory = HyperlinkInfoFactory.getInstance();

		final List<FileLinkMatch> matches = detectPaths(line);
		for(final FileLinkMatch match: matches) {
			final String path = PathUtil.getFileName(match.path);
			List<VirtualFile> matchingFiles = fileCache.get(path);

			if (null == matchingFiles) {
				matchingFiles = getResultItemsFileFromBasename(path);
				if (null == matchingFiles || matchingFiles.isEmpty()) {
					continue;
				}
			}

			if (matchingFiles.isEmpty()) {
				continue;
			}

			final List<VirtualFile> bestMatchingFiles = findBestMatchingFiles(match, matchingFiles);
			if (bestMatchingFiles != null && !bestMatchingFiles.isEmpty()) {
				matchingFiles = bestMatchingFiles;
			}
			final HyperlinkInfo linkInfo = hyperlinkInfoFactory.createMultipleFilesHyperlinkInfo(
					matchingFiles,
					match.linkedRow < 0 ? 0 : match.linkedRow - 1,
					project
			);

			results.add(new Result(
					startPoint + match.start,
					startPoint + match.end,
					linkInfo)
			);
		}

		return results;
	}

	private List<VirtualFile> findBestMatchingFiles(final FileLinkMatch match, final List<VirtualFile> matchingFiles) {
		return findBestMatchingFiles(generalizePath(match.path), matchingFiles);
	}

	private List<VirtualFile> findBestMatchingFiles(final String generalizedMatchPath,
			final List<VirtualFile> matchingFiles) {
		final List<VirtualFile> foundFiles = getFilesByPath(generalizedMatchPath, matchingFiles);
		if (!foundFiles.isEmpty()) {
			return foundFiles;
		}
		final String widerMetchingPath = dropOneLevelFromRoot(generalizedMatchPath);
		if (widerMetchingPath != null) {
			return findBestMatchingFiles(widerMetchingPath, matchingFiles);
		}
		return null;
	}

	private List<VirtualFile> getFilesByPath(final String generalizedMatchPath, final List<VirtualFile> matchingFiles) {
		final List<VirtualFile> matchedFiles = new ArrayList<>();
		for (final VirtualFile matchedFile : matchingFiles) {
			final String generalizedFilePath = generalizePath(matchedFile.getPath());
			if (generalizedFilePath.endsWith(generalizedMatchPath)) {
				matchedFiles.add(matchedFile);
			}
		}
		return matchedFiles;
	}

	private String dropOneLevelFromRoot(final String path) {
		if (path.contains("/")) {
			return path.substring(path.indexOf('/')+1);
		} else {
			return null;
		}
	}

	private String generalizePath(final String path) {
		return path.replace('\\', '/');
	}

	public List<VirtualFile> getResultItemsFileFromBasename(final String match) {
		return getResultItemsFileFromBasename(match, 0);
	}

	public List<VirtualFile> getResultItemsFileFromBasename(final String match, final int depth) {
		final ArrayList<VirtualFile> matches = new ArrayList<>();
		final char packageSeparator = '.';
		final int index = match.lastIndexOf(packageSeparator);
		if (-1 >= index) {
			return matches;
		}
		final String basename = match.substring(index + 1);
		final String origin = match.substring(0, index);
		final String path = origin.replace(packageSeparator, File.separatorChar);
		if (0 >= basename.length()) {
			return matches;
		}
		if (!fileBaseCache.containsKey(basename)) {
			/* Try to search deeper down the rabbit hole */
			if (depth <= maxSearchDepth) {
				return getResultItemsFileFromBasename(origin, depth + 1);
			}
			return matches;
		}
		for (final VirtualFile file : fileBaseCache.get(basename)) {
			final VirtualFile parent = file.getParent();
			if (null == parent) {
				continue;
			}
			if (!matchSource(parent.getPath(), path)) {
				continue;
			}
			matches.add(file);
		}
		return matches;
	}

	private void createFileCache() {
		projectRootManager.getFileIndex().iterateContent(
				new AwesomeProjectFilesIterator(fileCache, fileBaseCache));
	}

	private List<String> getSourceRoots() {
		final VirtualFile[] contentSourceRoots = projectRootManager.getContentSourceRoots();
		final List<String> roots = new ArrayList<>();
		for (final VirtualFile root : contentSourceRoots) {
			roots.add(root.getPath());
		}
		return roots;
	}

	private boolean matchSource(final String parent, final String path) {
		for (final String srcRoot : srcRoots) {
			if ((srcRoot + File.separatorChar + path).equals(parent)) {
				return true;
			}
		}
		return false;
	}

	@NotNull
	public List<FileLinkMatch> detectPaths(@NotNull final String line) {
		fileMatcher.reset(line);
		final List<FileLinkMatch> results = new LinkedList<>();
		while (fileMatcher.find()) {
			final String match = fileMatcher.group("link");
			final String row = fileMatcher.group("row");
			final String col = fileMatcher.group("col");
			results.add(new FileLinkMatch(match, fileMatcher.group("path"),
					fileMatcher.start(), fileMatcher.end(),
					null != row ? Integer.parseInt(row) : 0,
					null != col ? Integer.parseInt(col) : 0));
		}
		return results;
	}

	@NotNull
	public List<URLLinkMatch> detectURLs(@NotNull final String line) {
		urlMatcher.reset(line);
		final List<URLLinkMatch> results = new LinkedList<>();
		while (urlMatcher.find()) {
			String match = urlMatcher.group("link");
			int startOffset = 0;
			int endOffset = 0;

			for (final String surrounding : new String[]{"()", "''"}) {
				final String start = "" + surrounding.charAt(0);
				final String end = "" + surrounding.charAt(1);
				if (match.startsWith(start)) {
					startOffset = 1;
					match = match.substring(1);
					if (match.endsWith(end)) {
						endOffset = 1;
						match = match.substring(0, match.length() - 1);
					}
				}
			}
			results.add(new URLLinkMatch(match, urlMatcher.start() + startOffset, urlMatcher.end() - endOffset));
		}
		return results;
	}
}
