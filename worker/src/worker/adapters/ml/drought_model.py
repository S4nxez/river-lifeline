from worker.domain.drought import DroughtEvent, DroughtModel, WeatherObservation


class BaselineDroughtModel:
    """Drought predictor trained on a region's weather history labelled with its past droughts.

    The trained model is plain data (DroughtModel, kind "baseline"): the backend stores it and sends it back with every
    forecast job, so nothing is kept here. To implement: build features per day from rolling windows (precipitation
    deficit, temperature anomaly, soil moisture), label each day with whether a drought started within the horizon,
    fit a classifier and return its coefficients as `parameters`; `predict` applies them to the latest window.
    """

    KIND = "baseline"

    def train(self, observations: list[WeatherObservation], droughts: list[DroughtEvent]) -> DroughtModel:
        raise NotImplementedError("BaselineDroughtModel.train: not implemented yet")

    def predict(self, model: DroughtModel, recent: list[WeatherObservation], horizon_days: int) -> float:
        raise NotImplementedError("BaselineDroughtModel.predict: not implemented yet")
