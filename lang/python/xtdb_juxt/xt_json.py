import json
from .types import QueryTail, Query
from .tx import TxOp


class XtdbJsonEncoder(json.JSONEncoder):
    def default(self, obj):
        if isinstance(obj, (Query, QueryTail, TxOp)):
            return obj.to_json()
        return super().default(obj)
