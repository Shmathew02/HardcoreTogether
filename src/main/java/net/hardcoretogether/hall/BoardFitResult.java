package net.hardcoretogether.hall;

/** A board autofit result for commands: the placement and what was measured. */
public record BoardFitResult(HallLayout.BoardPlacement placement, String note) {
}
