# Task playback: from click to back, the intermediate detail page must remain visible

Signal: a click on the list is followed by Back, yet playback shows the list twice in a row; or the click circle appears on the page after returning. Choosing a single frame by round alone loses both the result frame and the origin of the marker, and batch actions may share a round.

Handling: associate the original frame, the result frame and the error by the actual call ID. If both frames exist, show them in order; the marker belongs only to the original frame. Report evidence locates the result screenshot via the observation and the call ID. The element center is only a positional indication, and semantic scrolling can only indicate direction. When an old record has only screenshots, do not infer the touch point. Uncollected cases and lost referenced files are handled separately.

Verification scope: the original [PR #348](https://github.com/wangzan101/Mika/pull/348) reproduced and verified the detail page, the red circle and swiping on one phone. After the split, `PlaybackTest`, `TaskReportViewModelTest` and `TaskPlaybackActionsTest` check event-to-frame association; unit tests cannot prove that the estimated element position on a given device matches the real touch point.
