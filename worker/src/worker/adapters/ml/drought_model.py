from worker.domain.drought import DroughtEvent, WeatherObservation


class BaselineDroughtModel:
    """Drought predictor trained on a region's weather history labelled with its past droughts.

    To implement: build features per day from rolling windows (precipitation deficit, temperature anomaly, soil
    moisture), label each day with whether a drought started within the horizon, fit a classifier (scikit-learn),
    and return its probability for the latest window in `predict`.
    """

    def train(self, observations: list[WeatherObservation], droughts: list[DroughtEvent]) -> None:
        raise NotImplementedError("BaselineDroughtModel.train: not implemented yet")

    def predict(self, recent: list[WeatherObservation], horizon_days: int) -> float:
        raise NotImplementedError("BaselineDroughtModel.predict: not implemented yet")
