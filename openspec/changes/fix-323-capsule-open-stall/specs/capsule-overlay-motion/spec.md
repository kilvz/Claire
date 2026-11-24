## ADDED Requirements

### Requirement: The expand animation reaches its terminal state within a finite time
The expand animation of the floating capsule overlay (card expansion, side-window appearance) SHALL reach its terminal state within a finite time: the animation value reaches 1 and the expand-completion callback has been called. A stalled frame clock (a suspension point waiting for a frame that never returns) MUST NOT leave the expand state at an intermediate value or cause the expand-completion callback to never run.

#### Scenario: expansion still settles when frames stall
- **WHEN** the frame clock stalls during the overlay expansion (the suspension point waiting for a frame never resumes)
- **THEN** after a time limit converted from the animation duration and the system animation speed multiplier, the animation value snaps directly to 1 and the expand-completion callback is called exactly once

#### Scenario: normal expansion is unaffected
- **WHEN** the frame clock is normal and the system is not slowed down
- **THEN** the expand animation plays out naturally for its original duration, the animation value reaches 1, the expand-completion callback is called as usual before the animation starts, and no fallback intervenes

#### Scenario: enough time under system slow motion
- **WHEN** the system animation speed multiplier is greater than 1 (slow motion)
- **THEN** the fallback time limit is converted by the multiplier (an extreme multiplier is counted at most as 10x), and a normal slow-motion expansion is not interrupted by the fallback

#### Scenario: settle immediately when animations are off
- **WHEN** the system has animations turned off (reduce motion)
- **THEN** the expansion plays no animation, settles immediately and calls the expand-completion callback, without taking the timeout path

### Requirement: The card geometry is complete in the expanded terminal state
The visible state after the card finishes expanding SHALL match the design geometry: the card takes its full width for the scale tier, is horizontally centered on the main screen, and has no edge clipping or residual half-open scale.

#### Scenario: the card is complete and centered after expansion
- **WHEN** with the main screen in portrait and the default scale tier, the user taps the floating capsule's card open and waits for the expansion to finish
- **THEN** the card area lies entirely within the screen, the left/right margins are broadly symmetric (centered), and the visible height is not below the lower bound of the expected height for that tier
